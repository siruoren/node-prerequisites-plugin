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

import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.util.ListBoxModel;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * A single system-level prerequisite rule.
 * <p>
 * Each rule defines a Groovy script (run in sandbox by default), a node selection
 * mode (all nodes, label-matched nodes, or regex-matched nodes), and optional
 * label/pattern constraints.
 */
public class SystemPrerequisiteRule {

    private static final Logger LOGGER = Logger.getLogger(SystemPrerequisiteRule.class.getName());

    public static final String MODE_ALL = "all";
    public static final String MODE_LABELS = "labels";
    public static final String MODE_REGEX = "regex";

    /** Command interpreter identifiers (values match {@code JobPrerequisites}). */
    public static final String INTERP_SHELL = "shell script";
    public static final String INTERP_WINDOWS = "windows batch command";
    public static final String INTERP_GROOVY = "groovy script";

    private final String name;
    private final String script;
    private String nodeSelectionMode = MODE_ALL;
    private String nodeLabels = "";
    private String nodePattern = "";
    private boolean sandbox = true;
    private String interpreter = INTERP_GROOVY;

    @DataBoundConstructor
    public SystemPrerequisiteRule(String name, String script) {
        this.name = name;
        this.script = script;
    }

    public String getName() {
        return name;
    }

    public String getScript() {
        return script;
    }

    public String getInterpreter() {
        return interpreter;
    }

    @DataBoundSetter
    public void setInterpreter(String interpreter) {
        this.interpreter = interpreter;
    }

    public String getNodeSelectionMode() {
        return nodeSelectionMode;
    }

    @DataBoundSetter
    public void setNodeSelectionMode(String nodeSelectionMode) {
        this.nodeSelectionMode = nodeSelectionMode;
    }

    public String getNodeLabels() {
        return nodeLabels;
    }

    @DataBoundSetter
    public void setNodeLabels(String nodeLabels) {
        this.nodeLabels = nodeLabels;
    }

    public String getNodePattern() {
        return nodePattern;
    }

    @DataBoundSetter
    public void setNodePattern(String nodePattern) {
        this.nodePattern = nodePattern;
    }

    public boolean isSandbox() {
        return sandbox;
    }

    @DataBoundSetter
    public void setSandbox(boolean sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * Check if this rule applies to the given node based on the node selection mode.
     */
    public boolean appliesToNode(Node node) {
        if (nodeSelectionMode == null || MODE_ALL.equals(nodeSelectionMode)) {
            return true;
        }

        String nodeName = node.getNodeName();
        if (nodeName == null || nodeName.isEmpty()) {
            nodeName = "Built-In";
        }

        if (MODE_LABELS.equals(nodeSelectionMode)) {
            if (nodeLabels == null || nodeLabels.trim().isEmpty()) {
                return true;
            }
            Set<LabelAtom> nodeLabelAtoms = node.getAssignedLabels() != null
                    ? node.getAssignedLabels() : new HashSet<LabelAtom>();
            Set<String> nodeLabelNames = new HashSet<>();
            for (LabelAtom atom : nodeLabelAtoms) {
                nodeLabelNames.add(atom.getName());
            }
            String[] requiredLabels = nodeLabels.split("[,\\s]+");
            for (String label : requiredLabels) {
                if (label.trim().isEmpty()) continue;
                if (nodeLabelNames.contains(label.trim())) {
                    return true;
                }
            }
            return false;
        }

        if (MODE_REGEX.equals(nodeSelectionMode)) {
            if (nodePattern == null || nodePattern.trim().isEmpty()) {
                return true;
            }
            try {
                Pattern pattern = Pattern.compile(nodePattern);
                return pattern.matcher(nodeName).find();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Invalid regex pattern ''{0}'': {1}",
                        new Object[]{nodePattern, e.getMessage()});
                return false;
            }
        }

        return true;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<SystemPrerequisiteRule> {

        @Override
        public String getDisplayName() {
            return "System Prerequisite Rule";
        }

        public ListBoxModel doFillInterpreterItems() {
            return new ListBoxModel()
                    .add("Shell Script", INTERP_SHELL)
                    .add("Windows Batch Command", INTERP_WINDOWS)
                    .add("Groovy Script", INTERP_GROOVY);
        }
    }
}
