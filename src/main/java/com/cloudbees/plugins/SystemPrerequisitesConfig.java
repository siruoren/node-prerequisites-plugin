/*
 * Copyright (C) 2012 CloudBees Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.cloudbees.plugins;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.Proc;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.remoting.Channel;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BatchFile;
import hudson.tasks.CommandInterpreter;
import hudson.tasks.Shell;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.jenkinsci.remoting.RoleChecker;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static hudson.model.TaskListener.NULL;

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
        List<SystemPrerequisiteRule> oldRules = this.rules;

        rules = new ArrayList<>();
        if (json.has("rules")) {
            JSONObject rulesObj = json.getJSONObject("rules");
            if (!rulesObj.isNullObject()) {
                rules = req.bindJSONToList(SystemPrerequisiteRule.class, rulesObj);
            }
        }

        if (oldRules != null && rules != null) {
            for (SystemPrerequisiteRule newRule : rules) {
                if (newRule == null) continue;
                if (newRule.getScripts() == null || newRule.getScripts().isEmpty()) {
                    for (SystemPrerequisiteRule oldRule : oldRules) {
                        if (oldRule != null && oldRule.getName() != null
                                && oldRule.getName().equals(newRule.getName())) {
                            List<PrerequisiteScript> effective = oldRule.getEffectiveScripts();
                            if (!effective.isEmpty()) {
                                newRule.setScripts(effective);
                            }
                            break;
                        }
                    }
                }
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
     * Convenience overload that omits the task name in logs.
     *
     * @param node the target node
     * @return {@code null} if all rules pass, a blocking reason string if any rule fails
     * @see #checkNode(Node, String)
     */
    public String checkNode(Node node) throws IOException, InterruptedException {
        return checkNode(node, null);
    }

    /**
     * Append the task name to a log message for traceability in the Jenkins log.
     */
    private static String taskSuffix(String taskName) {
        return (taskName != null && !taskName.isEmpty()) ? " [task: " + taskName + "]" : "";
    }

    /**
     * Run all applicable system-level rules against the given node.
     * Each rule may contain multiple {@link PrerequisiteScript} entries; each
     * script is executed <strong>on the node itself</strong> via Jenkins
     * Remoting ({@link Channel#call}) and can target specific nodes via
     * fuzzy pattern matching on the node name.
     *
     * @param node     the target node
     * @param taskName the name of the queued task (job) this check is being run for;
     *                included in log messages for traceability (may be {@code null})
     * @return {@code null} if all rules pass, a blocking reason string if any rule fails
     */
    public String checkNode(Node node, String taskName) throws IOException, InterruptedException {
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

            List<PrerequisiteScript> effectiveScripts = rule.getEffectiveScripts();
            if (effectiveScripts.isEmpty()) {
                continue;
            }

            for (PrerequisiteScript pscript : effectiveScripts) {
                if (pscript == null) continue;
                if (!pscript.appliesToNode(nodeName)) {
                    continue;
                }

                String reason = runScriptOnNode(
                        pscript.getScript(),
                        pscript.getInterpreter(),
                        node, nodeName,
                        rule.getName(),
                        pscript.getNodePattern(),
                        taskName);
                if (reason != null) {
                    return reason;
                }
            }
        }
        return null;
    }

    /**
     * Execute a single prerequisite script on the target node.
     * Dispatches to {@link #runGroovyOnNode} for Groovy scripts or
     * {@link #runInterpreterOnNode} for Shell / Batch scripts.
     *
     * @return {@code null} if the script passes, a blocking reason string if it fails
     */
    private String runScriptOnNode(String script, String interpreter, Node node,
                                   String nodeName, String ruleName,
                                   String scriptLabel, String taskName)
            throws IOException, InterruptedException {
        if (interpreter == null || SystemPrerequisiteRule.INTERP_GROOVY.equals(interpreter)) {
            return runGroovyOnNode(script, node, nodeName, ruleName, scriptLabel, taskName);
        } else {
            String reason = runInterpreterOnNode(script, interpreter, node,
                    ruleName, nodeName, taskName, scriptLabel);
            return reason;
        }
    }

    /**
     * Run a Groovy sandbox script on the target node via Jenkins Remoting.
     *
     * @return {@code null} if the script returns {@code true}, a blocking reason otherwise
     */
    private String runGroovyOnNode(String script, Node node, String nodeName,
                                   String ruleName, String scriptLabel, String taskName)
            throws IOException, InterruptedException {
        Map<String, Object> variables = buildBinding(node, nodeName);
        String labelSuffix = (scriptLabel != null && !scriptLabel.isEmpty()
                && !"*".equals(scriptLabel))
                ? " [pattern: " + scriptLabel + "]" : "";

        Computer computer = node.toComputer();
        boolean passed;

        if (computer == null) {
            passed = runLocal(script, variables);
        } else {
            VirtualChannel channel = computer.getChannel();
            if (channel != null) {
                GroovySandboxExecutor executor = new GroovySandboxExecutor(script, variables);
                hudson.remoting.Future<Boolean> rf = channel.callAsync(executor);
                try {
                    passed = rf.get(checkTimeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    rf.cancel(true);
                    String msg = "System prerequisite '" + ruleName + "'" + labelSuffix
                            + " timed out on node: " + nodeName
                            + " (timeout: " + checkTimeoutSeconds + "s)"
                            + taskSuffix(taskName);
                    LOGGER.log(Level.WARNING, msg);
                    return msg;
                } catch (InterruptedException e) {
                    rf.cancel(true);
                    String msg = "System prerequisite '" + ruleName + "'" + labelSuffix
                            + " was interrupted on node: " + nodeName
                            + taskSuffix(taskName);
                    LOGGER.log(Level.WARNING, msg);
                    return msg;
                } catch (ExecutionException e) {
                    rf.cancel(true);
                    String msg = "System prerequisite '" + ruleName + "'" + labelSuffix
                            + " failed on node: " + nodeName + taskSuffix(taskName);
                    Throwable cause = e.getCause();
                    LOGGER.log(Level.WARNING, msg
                            + (cause != null ? " (cause: " + cause + ")" : ""), e);
                    return msg;
                }
            } else {
                passed = runLocal(script, variables);
            }
        }

        if (!passed) {
            String msg = "System prerequisite '" + ruleName + "'" + labelSuffix
                    + " not met on node: " + nodeName + taskSuffix(taskName);
            LOGGER.log(Level.INFO, msg);
            return msg;
        }
        return null;
    }

    private boolean runLocal(String script, Map<String, Object> variables) {
        GroovySandboxExecutor executor = new GroovySandboxExecutor(script, variables);
        return executor.call();
    }

    /**
     * Run a Shell / Windows Batch prerequisite script <strong>as a process on the target node</strong>.
     * Mirrors the job-level prerequisite execution: create the script file on the node, launch it,
     * and enforce {@link #checkTimeoutSeconds} (killing the process on timeout).
     *
     * @return {@code null} if the script exits 0, otherwise a blocking reason string.
     */
    private String runInterpreterOnNode(String script, String interpreter, Node node,
                                        String ruleName, String nodeName, String taskName,
                                        String scriptLabel) {
        final String safeTask = (taskName != null && !taskName.isEmpty()) ? taskName : "<unknown>";
        String labelSuffix = (scriptLabel != null && !scriptLabel.isEmpty()
                && !"*".equals(scriptLabel))
                ? " [pattern: " + scriptLabel + "]" : "";
        Computer computer = node.toComputer();
        if (computer == null) {
            return "System prerequisite '" + ruleName + "'" + labelSuffix
                    + " cannot be verified: node '" + nodeName + "' is offline"
                    + taskSuffix(safeTask);
        }
        FilePath root = node.getRootPath();
        if (root == null) {
            return "System prerequisite '" + ruleName + "'" + labelSuffix
                    + " cannot be verified: node '" + nodeName + "' root path unavailable"
                    + taskSuffix(safeTask);
        }

        CommandInterpreter ci = getCommandInterpreter(script, interpreter);
        ExecutorService killPool = Executors.newSingleThreadExecutor();
        try {
            FilePath scriptFile = ci.createScriptFile(root);
            String[] envs = buildNodeEnvironment(node);
            hudson.Proc proc = node.createLauncher(NULL).launch()
                    .cmds(ci.buildCommandLine(scriptFile))
                    .envs(envs)
                    .stdout(NULL).pwd(root).start();

            Future<Integer> joinFuture = killPool.submit(new Callable<Integer>() {
                public Integer call() throws Exception {
                    return proc.join();
                }
            });

            try {
                int r = joinFuture.get(checkTimeoutSeconds, TimeUnit.SECONDS);
                return r == 0 ? null : "System prerequisite '" + ruleName + "'" + labelSuffix
                        + " not met on node: " + nodeName + taskSuffix(safeTask);
            } catch (TimeoutException e) {
                LOGGER.log(Level.WARNING, "Prerequisite check timed out for task {0} on {1} after {2}s, killing process",
                        new Object[]{safeTask, nodeName, checkTimeoutSeconds});
                try {
                    proc.kill();
                } catch (IOException killEx) {
                    LOGGER.log(Level.WARNING, "Failed to kill timed-out process for task {0} on {1}: {2}",
                            new Object[]{safeTask, nodeName, killEx.getMessage()});
                } finally {
                    joinFuture.cancel(true);
                }
                return "System prerequisite '" + ruleName + "'" + labelSuffix
                        + " timed out on node: " + nodeName
                        + " (timeout: " + checkTimeoutSeconds + "s)" + taskSuffix(safeTask);
            } catch (ExecutionException e) {
                LOGGER.log(Level.WARNING, "Prerequisite check failed for task {0} on {1}: {2}",
                        new Object[]{safeTask, nodeName, e.getCause() != null ? e.getCause().getMessage() : e.getMessage()});
                return "System prerequisite '" + ruleName + "'" + labelSuffix
                        + " failed on node: " + nodeName + taskSuffix(safeTask);
            } finally {
                killPool.shutdownNow();
            }
        } catch (IOException | InterruptedException e) {
            LOGGER.log(Level.WARNING, "Failed to launch prerequisite check for task {0} on {1}: {2}",
                    new Object[]{safeTask, nodeName, e.getMessage()});
            return "System prerequisite '" + ruleName + "'" + labelSuffix
                    + " failed to launch on node: " + nodeName
                    + " (" + e.getMessage() + ")" + taskSuffix(safeTask);
        }
    }

    private CommandInterpreter getCommandInterpreter(String script, String interpreter) {
        if (SystemPrerequisiteRule.INTERP_WINDOWS.equals(interpreter)) {
            return new BatchFile(script);
        }
        return new Shell(script);
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

    /**
     * Build environment variables exposed to a Shell / Batch prerequisite script
     * (NODE_NAME, NODE_HOSTNAME, NODE_IP, NODE_LABELS). Mirrors the job-level
     * prerequisite environment so scripts can rely on the same values.
     */
    private String[] buildNodeEnvironment(Node node) {
        List<String> envs = new ArrayList<>();

        String nodeName = node.getNodeName();
        if (nodeName == null || nodeName.isEmpty()) {
            nodeName = "Built-In";
        }
        envs.add("NODE_NAME=" + nodeName);

        String hostName = "";
        String hostIp = "";
        try {
            Computer computer = node.toComputer();
            if (computer != null) {
                VirtualChannel channel = computer.getChannel();
                if (channel != null) {
                    // Remote agent - retrieve hostname and IP from the agent itself.
                    String[] nodeInfo = channel.call(new NodeInfoCallable());
                    hostName = nodeInfo[0];
                    hostIp = nodeInfo[1];
                } else {
                    // Built-in node (no channel) - retrieve locally.
                    InetAddress addr = InetAddress.getLocalHost();
                    hostName = addr.getHostName();
                    hostIp = addr.getHostAddress();
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get node host info for {0}: {1}",
                    new Object[]{nodeName, e.getMessage()});
        }
        envs.add("NODE_HOSTNAME=" + hostName);
        envs.add("NODE_IP=" + hostIp);

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
        envs.add("NODE_LABELS=" + labels.toString());

        return envs.toArray(new String[0]);
    }

    /**
     * Callable executed on the remote agent to retrieve its hostname and IP address.
     */
    private static class NodeInfoCallable implements hudson.remoting.Callable<String[], IOException> {
        private static final long serialVersionUID = 1L;

        @Override
        public String[] call() throws IOException {
            InetAddress addr = InetAddress.getLocalHost();
            return new String[]{addr.getHostName(), addr.getHostAddress()};
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {
            // No privileged operation beyond reading the agent hostname/IP.
        }
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return "System Prerequisites";
    }
}
