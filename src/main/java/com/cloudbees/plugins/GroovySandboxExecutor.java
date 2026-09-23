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

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import hudson.remoting.MasterToSlaveCallable;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executes Groovy scripts in a restricted sandbox environment
 * <strong>on the remote node</strong> via Jenkins Remoting.
 * <p>
 * Implements {@link MasterToSlaveCallable} so it can be sent through a
 * {@link hudson.remoting.Channel} to the agent JVM. All data it needs
 * (script source, bindings) is serializable.
 * <p>
 * The sandbox uses {@link SecureASTCustomizer} to restrict dangerous operations:
 * <ul>
 *   <li>Imports are limited to a safe subset</li>
 *   <li>Receivers such as {@code System}, {@code Runtime}, {@code ProcessBuilder}
 *       are blacklisted</li>
 * </ul>
 * The script receives a {@link Binding} with node information variables.
 * If the script returns {@code false} (or throws), the prerequisite is not met.
 */
public class GroovySandboxExecutor extends MasterToSlaveCallable<Boolean, RuntimeException>
        implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(GroovySandboxExecutor.class.getName());

    private final String script;
    private final Map<String, Object> variables;

    public GroovySandboxExecutor(String script, Map<String, Object> variables) {
        this.script = script;
        this.variables = variables != null ? variables : new HashMap<String, Object>();
    }

    @Override
    public Boolean call() throws RuntimeException {
        try {
            Binding binding = new Binding(variables);
            GroovyShell shell = createSandboxShell(binding);
            Object result = shell.evaluate(script);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return result != null;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Groovy sandbox script failed: {0}", e.getMessage());
            return false;
        }
    }

    /**
     * Create a GroovyShell with sandbox restrictions.
     */
    private static GroovyShell createSandboxShell(Binding binding) {
        CompilerConfiguration config = new CompilerConfiguration();

        SecureASTCustomizer customizer = new SecureASTCustomizer();

        customizer.setImportsWhitelist(Arrays.asList(
                "java.lang.Math",
                "java.lang.String",
                "java.lang.Integer",
                "java.lang.Long",
                "java.lang.Double",
                "java.lang.Boolean",
                "java.util.ArrayList",
                "java.util.List",
                "java.util.Map",
                "java.util.HashMap",
                "java.util.Set",
                "java.util.HashSet",
                "java.util.Arrays",
                "java.util.Collections",
                "java.io.File",
                "java.net.InetAddress"
        ));

        customizer.setStaticImportsWhitelist(Arrays.asList(
                "java.lang.Math",
                "java.util.Collections",
                "java.util.Arrays"
        ));

        customizer.setIndirectImportAllowed(true);

        customizer.setReceiversBlackList(Arrays.asList(
                System.class.getName(),
                Runtime.class.getName(),
                Thread.class.getName(),
                ClassLoader.class.getName(),
                "java.lang.ProcessBuilder",
                "java.lang.Process",
                "groovy.lang.GroovyShell",
                "groovy.lang.GroovyClassLoader"
        ));

        ImportCustomizer importCustomizer = new ImportCustomizer();
        importCustomizer.addImports(
                "java.io.File",
                "java.net.InetAddress",
                "java.util.ArrayList",
                "java.util.List",
                "java.util.Map",
                "java.util.HashMap",
                "java.util.Set",
                "java.util.HashSet",
                "java.util.Arrays",
                "java.util.Collections"
        );

        config.addCompilationCustomizers(importCustomizer, customizer);

        return new GroovyShell(binding, config);
    }
}
