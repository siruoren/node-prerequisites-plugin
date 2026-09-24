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

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * A single system-level prerequisite rule.
 * <p>
 * Each rule defines a node selection mode (all nodes, label-matched nodes, or
 * regex-matched nodes) and one or more {@link PrerequisiteScript} entries.
 * Each script within a rule can further target specific nodes via fuzzy
 * pattern matching on the node name.
 * <p>
 * For backward compatibility, a rule may still carry a single {@code script}
 * field (with {@code interpreter} and {@code sandbox} defaults). When the
 * {@code scripts} list is non-empty, it takes precedence over the legacy
 * single-script fields.
 */
public class SystemPrerequisiteRule implements Describable<SystemPrerequisiteRule> {

    private static final Logger LOGGER = Logger.getLogger(SystemPrerequisiteRule.class.getName());

    public static final String MODE_ALL = "all";
    public static final String MODE_LABELS = "labels";
    public static final String MODE_REGEX = "regex";

    /** Command interpreter identifiers (values match {@code JobPrerequisites}). */
    public static final String INTERP_SHELL = "shell script";
    public static final String INTERP_WINDOWS = "windows batch command";
    public static final String INTERP_GROOVY = "groovy script";

    private final String name;
    private String script;
    private List<PrerequisiteScript> scripts;
    private String nodeSelectionMode = MODE_ALL;
    private String nodeLabels = "";
    private String nodePattern = "";
    private boolean sandbox = true;
    private String interpreter = INTERP_GROOVY;

    @DataBoundConstructor
    public SystemPrerequisiteRule(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getScript() {
        return script;
    }

    @DataBoundSetter
    public void setScript(String script) {
        this.script = script;
    }

    public List<PrerequisiteScript> getScripts() {
        return scripts != null ? scripts : Collections.<PrerequisiteScript>emptyList();
    }

    @DataBoundSetter
    public void setScripts(List<PrerequisiteScript> scripts) {
        this.scripts = scripts;
    }

    /**
     * Return the effective list of scripts to execute.
     * If the {@code scripts} list is non-empty, use it; otherwise, if the
     * legacy single {@code script} field is set, wrap it in a single-element
     * list using the rule-level {@code interpreter} and {@code sandbox}.
     *
     * @return non-null list (empty if no scripts configured)
     */
    public List<PrerequisiteScript> getEffectiveScripts() {
        if (scripts != null && !scripts.isEmpty()) {
            return scripts;
        }
        if (script != null && !script.trim().isEmpty()) {
            PrerequisiteScript legacy = new PrerequisiteScript(script);
            legacy.setInterpreter(interpreter);
            legacy.setSandbox(sandbox);
            legacy.setNodePattern("*");
            List<PrerequisiteScript> list = new ArrayList<>();
            list.add(legacy);
            return list;
        }
        return Collections.emptyList();
    }

    @Override
    public Descriptor<SystemPrerequisiteRule> getDescriptor() {
        return Jenkins.get().getDescriptorOrDie(SystemPrerequisiteRule.class);
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
            ListBoxModel m = new ListBoxModel();
            m.add("Shell Script", INTERP_SHELL);
            m.add("Windows Batch Command", INTERP_WINDOWS);
            m.add("Groovy Script", INTERP_GROOVY);
            return m;
        }
    }
}
