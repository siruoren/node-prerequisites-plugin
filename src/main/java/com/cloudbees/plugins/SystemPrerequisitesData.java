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
import hudson.init.Initializer;
import hudson.init.InitMilestone;
import hudson.XmlFile;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holder for all node-prerequisites settings, persisted inside the main
 * {@code $JENKINS_HOME/config.xml} file.
 * <p>
 * Jenkins only serializes objects reachable from its own object graph into
 * {@code config.xml}. The one supported extension point for plugins is the
 * {@code globalNodeProperties} list (see
 * {@code jenkins.model.GlobalNodePropertiesConfiguration}), so this class is a
 * {@link NodeProperty} instance kept in {@link Jenkins#getGlobalNodeProperties()}.
 * The data appears in {@code config.xml} as:
 * <pre>
 * &lt;globalNodeProperties&gt;
 *   &lt;com.cloudbees.plugins.SystemPrerequisitesData&gt;...&lt;/com.cloudbees.plugins.SystemPrerequisitesData&gt;
 * &lt;/globalNodeProperties&gt;
 * </pre>
 * The editing UI stays on the standalone "Manage Jenkins &gt; System Prerequisites"
 * page ({@link SystemPrerequisitesConfig}); nothing is editable from
 * "Configure System" (see {@link DescriptorImpl}).
 */
public class SystemPrerequisitesData extends NodeProperty<Node> {

    private static final Logger LOGGER = Logger.getLogger(SystemPrerequisitesData.class.getName());

    /**
     * Default retry interval for job-level prerequisite checks (seconds).
     * Job-level checks retry forever with this fixed interval.
     */
    public static final int JOB_RETRY_INTERVAL_SECONDS = 60;

    private List<SystemPrerequisiteRule> rules;
    private int retryCount;
    private int retryIntervalSeconds = 30;
    private int checkTimeoutSeconds = 60;
    private int maxConcurrentChecks = 4;

    public List<SystemPrerequisiteRule> getRules() {
        return rules != null ? rules : Collections.<SystemPrerequisiteRule>emptyList();
    }

    @DataBoundSetter
    public void setRules(List<SystemPrerequisiteRule> rules) {
        this.rules = rules;
    }

    /**
     * System-level retry count. {@code 0} means retry forever (no limit).
     */
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

    /**
     * Max number of prerequisite checks (system + job level) running concurrently.
     * {@code 0} or negative means unlimited.
     */
    public int getMaxConcurrentChecks() {
        return maxConcurrentChecks;
    }

    @DataBoundSetter
    public void setMaxConcurrentChecks(int maxConcurrentChecks) {
        this.maxConcurrentChecks = maxConcurrentChecks;
    }

    /**
     * Returns the singleton data holder, creating and registering it on demand.
     */
    public static SystemPrerequisitesData get() {
        Jenkins j = Jenkins.get();
        DescribableList<NodeProperty<?>, NodePropertyDescriptor> globals = j.getGlobalNodeProperties();
        for (NodeProperty<?> p : globals) {
            if (p instanceof SystemPrerequisitesData) {
                return (SystemPrerequisitesData) p;
            }
        }
        SystemPrerequisitesData data = new SystemPrerequisitesData();
        globals.add(data);
        return data;
    }

    /**
     * Keep this instance alive no matter what the submitted form contains.
     * <p>
     * {@code GlobalNodePropertiesConfiguration} rebuilds the
     * {@code globalNodeProperties} list on every "Configure System" save. Our
     * settings are not editable there (they live on the Manage Jenkins page), so
     * no form data will be submitted for us; returning {@code this} ensures the
     * instance is never dropped.
     */
    @Override
    public NodeProperty<?> reconfigure(StaplerRequest req, JSONObject form) {
        return this;
    }

    @Extension
    public static class DescriptorImpl extends NodePropertyDescriptor {

        public DescriptorImpl() {
            super();
        }

        @Override
        public String getDisplayName() {
            return "System Prerequisites (configured under Manage Jenkins)";
        }

        /**
         * Only applicable to the built-in node so that
         * {@code GlobalNodePropertiesConfiguration.configure()} includes our
         * descriptor in its {@code rebuild()} list (which filters by
         * {@code isApplicable(node.getClass())} with the Jenkins instance).
         * Without this, saving "Configure System" would drop our data.
         */
        @Override
        public boolean isApplicable(Class<? extends Node> type) {
            return Hudson.class.isAssignableFrom(type);
        }

        /**
         * One-time migration: if settings still live in the legacy standalone file
         * {@code $JENKINS_HOME/node-prerequisites.xml}, load them into
         * {@code config.xml} and rename the old file to {@code .migrated}.
         */
        @Initializer(after = InitMilestone.JOB_LOADED)
        public void migrateLegacyFile() throws IOException {
            Jenkins j = Jenkins.get();
            File legacy = new File(j.getRootDir(), "node-prerequisites.xml");
            if (!legacy.exists()) {
                return;
            }
            try {
                XmlFile f = new XmlFile(legacy);
                SystemPrerequisitesData data = new SystemPrerequisitesData();
                f.unmarshal(data);
                DescribableList<NodeProperty<?>, NodePropertyDescriptor> globals = j.getGlobalNodeProperties();
                boolean exists = false;
                for (NodeProperty<?> p : globals) {
                    if (p instanceof SystemPrerequisitesData) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    globals.add(data);
                }
                j.save();
                File renamed = new File(j.getRootDir(), "node-prerequisites.xml.migrated");
                if (!legacy.renameTo(renamed)) {
                    LOGGER.log(Level.WARNING,
                            "Migrated node-prerequisites.xml into config.xml but could not rename the legacy file");
                } else {
                    LOGGER.log(Level.INFO,
                            "Migrated legacy node-prerequisites.xml into config.xml (globalNodeProperties); "
                                    + "legacy file kept as node-prerequisites.xml.migrated");
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to migrate legacy node-prerequisites.xml: " + e, e);
            }
        }
    }
}
