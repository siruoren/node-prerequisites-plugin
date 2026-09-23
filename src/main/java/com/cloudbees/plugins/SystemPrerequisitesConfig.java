/*
 * Copyright (C) 2012 CloudBees Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * along with this program; if not, see <http://www.gnu.org/licenses/>.
 */

package com.cloudbees.plugins;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.remoting.Channel;
import hudson.remoting.VirtualChannel;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * System-level (global) prerequisites configuration.
 * <p>
 * Stored under {@code Manage Jenkins > System Configuration}.
 * Rules defined here run <strong>before</strong> job-level prerequisites.
 * All scripts run <strong>on the target node</strong> via Jenkins Remoting,
 * not on the Jenkins controller.
 * <p>
 * Retry mechanism: when a check fails, it will be retried up to
 * {@link #retryCount} times with a delay of {@link #retryIntervalSeconds}
 * seconds between attempts. Once a retry passes, the node is accepted.
 */
@Extension
public class SystemPrerequisitesConfig extends GlobalConfiguration {

    private static final Logger LOGGER = Logger.getLogger(SystemPrerequisitesConfig.class.getName());

    private List<SystemPrerequisiteRule> rules;
    private int retryCount = 3;
    private int retryIntervalSeconds = 30;
    private int checkTimeoutSeconds = 60;

    @DataBoundConstructor
    public SystemPrerequisitesConfig() {
        load();
    }

    public List<SystemPrerequisiteRule> getRules() {
        return rules != null ? rules : Collections.<SystemPrerequisiteRule>emptyList();
    }

    @DataBoundSetter
    public void setRules(List<SystemPrerequisiteRule> rules) {
        this.rules = rules;
    }

    public int getRetryCount() {
        return retryCount;
    }

    @DataBoundSetter
    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getRetryIntervalSeconds() {
        return retryIntervalSeconds;
    }

    @DataBoundSetter
    public void setRetryIntervalSeconds(int retryIntervalSeconds) {
        this.retryIntervalSeconds = retryIntervalSeconds;
    }

    public int getCheckTimeoutSeconds() {
        return checkTimeoutSeconds;
    }

    @DataBoundSetter
    public void setCheckTimeoutSeconds(int checkTimeoutSeconds) {
        this.checkTimeoutSeconds = checkTimeoutSeconds;
    }

    public static SystemPrerequisitesConfig get() {
        return GlobalConfiguration.all().get(SystemPrerequisitesConfig.class);
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        rules = new ArrayList<>();
        if (json.has("rules")) {
            JSONObject rulesObj = json.getJSONObject("rules");
            if (!rulesObj.isNullObject()) {
                rules = req.bindJSONToList(SystemPrerequisiteRule.class, rulesObj);
            }
        }
        retryCount = json.optInt("retryCount", 3);
        retryIntervalSeconds = json.optInt("retryIntervalSeconds", 30);
        checkTimeoutSeconds = json.optInt("checkTimeoutSeconds", 60);
        save();
        return true;
    }

    /**
     * Run all applicable system-level rules against the given node.
     * Each rule's Groovy script is executed <strong>on the node itself</strong>
     * via Jenkins Remoting ({@link Channel#call}).
     *
     * @param node the target node
     * @return {@code null} if all rules pass, a blocking reason string if any rule fails
     */
    public String checkNode(Node node) throws IOException, InterruptedException {
        if (rules == null || rules.isEmpty()) {
            return null;
        }

        String nodeName = node.getNodeName();
        if (nodeName == null || nodeName.isEmpty()) {
            nodeName = "Built-In";
        }

        for (SystemPrerequisiteRule rule : rules) {
            if (rule == null) continue;
            if (!rule.appliesToNode(node)) {
                continue;
            }

            Map<String, Object> variables = buildBinding(node, nodeName);

            boolean passed;
            Computer computer = node.toComputer();
            if (computer == null) {
                passed = runLocal(rule.getScript(), variables);
            } else {
                VirtualChannel channel = computer.getChannel();
                if (channel != null) {
                    GroovySandboxExecutor executor = new GroovySandboxExecutor(rule.getScript(), variables);
                    hudson.remoting.Future<Boolean> rf = channel.callAsync(executor);
                    try {
                        passed = rf.get(checkTimeoutSeconds, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        rf.cancel(true);
                        String msg = "System prerequisite '" + rule.getName()
                                + "' timed out on node: " + nodeName
                                + " (timeout: " + checkTimeoutSeconds + "s)";
                        LOGGER.log(Level.WARNING, msg);
                        return msg;
                    } catch (InterruptedException e) {
                        rf.cancel(true);
                        String msg = "System prerequisite '" + rule.getName()
                                + "' was interrupted on node: " + nodeName;
                        LOGGER.log(Level.WARNING, msg);
                        return msg;
                    } catch (ExecutionException e) {
                        rf.cancel(true);
                        String msg = "System prerequisite '" + rule.getName()
                                + "' failed on node: " + nodeName;
                        Throwable cause = e.getCause();
                        LOGGER.log(Level.WARNING, msg
                                + (cause != null ? " (cause: " + cause + ")" : ""), e);
                        return msg;
                    }
                } else {
                    passed = runLocal(rule.getScript(), variables);
                }
            }

            if (!passed) {
                String msg = "System prerequisite '" + rule.getName()
                        + "' not met on node: " + nodeName;
                LOGGER.log(Level.INFO, msg);
                return msg;
            }
        }
        return null;
    }

    private boolean runLocal(String script, Map<String, Object> variables) {
        GroovySandboxExecutor executor = new GroovySandboxExecutor(script, variables);
        return executor.call();
    }

    private Map<String, Object> buildBinding(Node node, String nodeName) {
        Map<String, Object> vars = new HashMap<>();

        vars.put("NODE_NAME", nodeName);

        String hostName = "";
        String hostIp = "";
        try {
            InetAddress addr = InetAddress.getLocalHost();
            hostName = addr.getHostName();
            hostIp = addr.getHostAddress();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get host info: {0}", e.getMessage());
        }
        vars.put("NODE_HOSTNAME", hostName);
        vars.put("NODE_IP", hostIp);

        StringBuilder labels = new StringBuilder();
        Set<LabelAtom> assignedLabels = node.getAssignedLabels();
        if (assignedLabels != null) {
            for (LabelAtom label : assignedLabels) {
                if (labels.length() > 0) {
                    labels.append(" ");
                }
                labels.append(label.getName());
            }
        }
        vars.put("NODE_LABELS", labels.toString());

        return vars;
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return "System Prerequisites";
    }
}
