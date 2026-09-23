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

import hudson.Extension;
import hudson.FilePath;
import hudson.model.*;
import hudson.model.queue.CauseOfBlockage;
import hudson.remoting.Channel;
import hudson.remoting.VirtualChannel;
import org.jenkinsci.remoting.RoleChecker;
import hudson.model.labels.LabelAtom;
import hudson.tasks.BatchFile;
import hudson.tasks.CommandInterpreter;
import hudson.tasks.Shell;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static hudson.model.TaskListener.NULL;

/**
 * @author: <a hef="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class JobPrerequisites extends JobProperty<AbstractProject<?, ?>> implements Action {

    private static final Logger LOGGER = Logger.getLogger(JobPrerequisites.class.getName());

    private final String script;
    private final String interpreter;

    public static final String SHELL_SCRIPT = "shell script";
    public static final String WINDOWS = "windows batch command";
    public static final String GROOVY = "groovy script";

    @DataBoundConstructor
    public JobPrerequisites(String script, String interpreter) {
        this.script = script;
        this.interpreter = interpreter;
    }

    public String getScript() {
        return script;
    }

    public String getInterpreter() {
        return interpreter;
    }

    /**
     * @return null if prerequisites are met on the target Node, else a blockage
     * @see #check(Node, String)
     */
    public CauseOfBlockage check(Node node) throws IOException, InterruptedException {
        return check(node, null);
    }

    /**
     * Run the job-level prerequisite script on the given node.
     *
     * @param node     the target node
     * @param taskName the name of the queued task (job) this check is being run for;
     *                included in log messages for traceability (may be {@code null})
     * @return null if prerequisites are met on the target Node, else a blockage
     */
    public CauseOfBlockage check(Node node, String taskName) throws IOException, InterruptedException {
        final String logTask = (taskName != null && !taskName.isEmpty()) ? taskName : "<unknown>";
        CommandInterpreter shell = getCommandInterpreter(this.script);
        FilePath root = node.getRootPath();
        if (root == null) return new CauseOfBlockage.BecauseNodeIsOffline(node); //offline ?

        FilePath scriptFile = shell.createScriptFile(root);
        shell.buildCommandLine(scriptFile);

        String[] envs = buildNodeEnvironment(node);

        int timeoutSeconds = getCheckTimeoutSeconds();

        hudson.Proc proc = node.createLauncher(NULL).launch()
                .cmds(shell.buildCommandLine(scriptFile))
                .envs(envs)
                .stdout(NULL).pwd(root).start();

        // Wait for the process with timeout; kill it if it exceeds the limit
        ExecutorService killPool = Executors.newSingleThreadExecutor();
        Future<Integer> joinFuture = killPool.submit(new Callable<Integer>() {
            public Integer call() throws Exception {
                return proc.join();
            }
        });

        try {
            int r = joinFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            return r == 0 ? null : new BecausePrerequisitesArentMet(node);
        } catch (TimeoutException e) {
            // Kill the timed-out process to avoid zombie accumulation
            LOGGER.log(Level.WARNING, "Prerequisite check timed out for task {0} on {1} after {2}s, killing process",
                    new Object[]{logTask, node.getNodeName(), timeoutSeconds});
            try {
                proc.kill();
            } catch (IOException killEx) {
                LOGGER.log(Level.WARNING, "Failed to kill timed-out process for task {0} on {1}: {2}",
                        new Object[]{logTask, node.getNodeName(), killEx.getMessage()});
            } finally {
                joinFuture.cancel(true);
            }
            return CauseOfBlockage.fromMessage(
                    Messages._JobPrerequisites_PrerequisiteCheckTimedOut(timeoutSeconds));
        } catch (java.util.concurrent.ExecutionException e) {
            LOGGER.log(Level.WARNING, "Prerequisite check failed for task {0} on {1}: {2}",
                    new Object[]{logTask, node.getNodeName(), e.getCause() != null ? e.getCause().getMessage() : e.getMessage()});
            return new BecausePrerequisitesArentMet(node);
        } finally {
            killPool.shutdownNow();
        }
    }

    private int getCheckTimeoutSeconds() {
        SystemPrerequisitesConfig config = SystemPrerequisitesConfig.get();
        return config != null ? config.getCheckTimeoutSeconds() : 60;
    }

    private CommandInterpreter getCommandInterpreter(String script) {
        if (WINDOWS.equals(interpreter)) {
            return new BatchFile(script);
        }
        if (GROOVY.equals(interpreter)) {
            return new GroovyScript(script);
        }
        return new Shell(script);
    }

    /**
     * Build environment variables with node information for the prerequisite script.
     * Exposes NODE_NAME, NODE_HOSTNAME, NODE_IP, and NODE_LABELS to the script.
     */
    private String[] buildNodeEnvironment(Node node) {
        List<String> envs = new ArrayList<>();

        // NODE_NAME
        String nodeName = node.getNodeName();
        if (nodeName == null || nodeName.isEmpty()) {
            nodeName = "Built-In";
        }
        envs.add("NODE_NAME=" + nodeName);

        // NODE_HOSTNAME and NODE_IP
        String hostName = "";
        String hostIp = "";
        try {
            Computer computer = node.toComputer();
            if (computer != null) {
                VirtualChannel channel = computer.getChannel();
                if (channel != null) {
                    // Remote agent - retrieve hostname and IP from the agent itself
                    String[] nodeInfo = channel.call(new NodeInfoCallable());
                    hostName = nodeInfo[0];
                    hostIp = nodeInfo[1];
                } else {
                    // Built-in node (no channel) - retrieve locally
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

        // NODE_LABELS
        StringBuilder labels = new StringBuilder();
        Set<LabelAtom> assignedLabels = node.getAssignedLabels();
        if (assignedLabels != null) {
            for (Label label : assignedLabels) {
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

    @Extension
    public final static class DescriptorImpl extends JobPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return "Check prerequisites before job can build on a node";
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return AbstractProject.class.isAssignableFrom(jobType);
        }

        @Override
        public JobProperty<?> newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            if (formData.isNullObject()) {
                return null;
            }
            JSONObject prerequisites = formData.getJSONObject("prerequisites");
            if (prerequisites.isNullObject()) {
                return null;
            }
            return req.bindJSON(JobPrerequisites.class, prerequisites);
        }

        public ListBoxModel doFillInterpreterItems() {
            return new ListBoxModel()
                    .add(SHELL_SCRIPT)
                    .add(WINDOWS)
                    .add(GROOVY);
        }

    }

    // fake implementations for Action, required to contribute the job configuration UI

    public String getDisplayName() {
        return null;
    }

    public String getIconFileName() {
        return null;
    }

    public String getUrlName() {
        return null;
    }

}
