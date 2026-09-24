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
 * License along with this program; if not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.cloudbees.plugins;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A single execution script within a {@link SystemPrerequisiteRule}.
 * <p>
 * Each script can target specific nodes via fuzzy pattern matching on the
 * node name. Supported wildcards:
 * <ul>
 *   <li>{@code *} &ndash; matches any sequence of characters</li>
 *   <li>{@code ?} &ndash; matches any single character</li>
 *   <li>Comma-separated patterns &ndash; any pattern matching is sufficient</li>
 * </ul>
 * Examples:
 * <ul>
 *   <li>{@code *} &ndash; all nodes (default)</li>
 *   <li>{@code build-*} &ndash; nodes whose name starts with {@code build-}</li>
 *   <li>{@code agent-1,agent-2} &ndash; only agent-1 and agent-2</li>
 *   <li>{@code *-linux,*-mac} &ndash; nodes ending with {@code -linux} or {@code -mac}</li>
 * </ul>
 */
public class PrerequisiteScript implements Describable<PrerequisiteScript> {

    private static final Logger LOGGER = Logger.getLogger(PrerequisiteScript.class.getName());

    private final String script;
    private String interpreter = SystemPrerequisiteRule.INTERP_GROOVY;
    private String nodePattern = "*";
    private boolean sandbox = true;

    @DataBoundConstructor
    public PrerequisiteScript(String script) {
        this.script = script;
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

    public String getNodePattern() {
        return nodePattern;
    }

    @DataBoundSetter
    public void setNodePattern(String nodePattern) {
        this.nodePattern = (nodePattern == null || nodePattern.trim().isEmpty())
                ? "*" : nodePattern.trim();
    }

    public boolean isSandbox() {
        return sandbox;
    }

    @DataBoundSetter
    public void setSandbox(boolean sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public Descriptor<PrerequisiteScript> getDescriptor() {
        return Jenkins.get().getDescriptorOrDie(PrerequisiteScript.class);
    }

    /**
     * Check if this script should run on the given node based on the
     * fuzzy node pattern.
     *
     * @param nodeName the node name to check (never {@code null})
     * @return {@code true} if the pattern matches the node name
     */
    public boolean appliesToNode(String nodeName) {
        if (nodePattern == null || nodePattern.trim().isEmpty()
                || "*".equals(nodePattern.trim())) {
            return true;
        }

        String[] patterns = nodePattern.split(",");
        for (String p : patterns) {
            p = p.trim();
            if (p.isEmpty()) continue;
            if (fuzzyMatch(p, nodeName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fuzzy-match a wildcard pattern against a string.
     * Converts {@code *} to {@code .*} and {@code ?} to {@code .},
     * escaping all other regex metacharacters.
     */
    private static boolean fuzzyMatch(String pattern, String text) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append('.');
                    break;
                case '.': case '(': case ')': case '[': case ']':
                case '{': case '}': case '+': case '^': case '$':
                case '|': case '\\':
                    regex.append('\\').append(c);
                    break;
                default:
                    regex.append(c);
            }
        }
        try {
            return Pattern.compile(regex.toString()).matcher(text).matches();
        } catch (PatternSyntaxException e) {
            LOGGER.log(Level.WARNING, "Invalid fuzzy pattern ''{0}'': {1}",
                    new Object[]{pattern, e.getMessage()});
            return false;
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<PrerequisiteScript> {

        @Override
        public String getDisplayName() {
            return "Prerequisite Script";
        }

        public ListBoxModel doFillInterpreterItems() {
            ListBoxModel m = new ListBoxModel();
            m.add("Shell Script", SystemPrerequisiteRule.INTERP_SHELL);
            m.add("Windows Batch Command", SystemPrerequisiteRule.INTERP_WINDOWS);
            m.add("Groovy Script", SystemPrerequisiteRule.INTERP_GROOVY);
            return m;
        }
    }
}
