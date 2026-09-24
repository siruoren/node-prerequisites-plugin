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
import hudson.model.Computer;
import hudson.model.Node;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Exposes a REST API for retrieving system-level prerequisite check results
 * for all Jenkins nodes.
 * <p>
 * Endpoint: {@code $JENKINS_URL/node-prerequisites-api/checkAllNodes}
 * <p>
 * Requires {@link Jenkins#ADMINISTER} permission.
 * <p>
 * Optional query parameters:
 * <ul>
 *   <li>{@code detailed=true} &ndash; include per-rule, per-script breakdown
 *       (runs every matching script, not just the first failure)</li>
 * </ul>
 * <p>
 * Response example (detailed):
 * <pre>{@code
 * {
 *   "timestamp": "2026-09-24T01:35:08Z",
 *   "totalNodes": 2,
 *   "passed": 1,
 *   "failed": 1,
 *   "nodes": [
 *     {
 *       "name": "agent-1",
 *       "online": true,
 *       "passed": true,
 *       "reason": "",
 *       "rules": [
 *         {
 *           "ruleName": "Disk Check",
 *           "appliesToNode": true,
 *           "passed": true,
 *           "scripts": [
 *             {
 *               "nodePattern": "agent-*",
 *               "interpreter": "shell script",
 *               "passed": true,
 *               "reason": ""
 *             }
 *           ]
 *         }
 *       ]
 *     },
 *     {
 *       "name": "agent-2",
 *       "online": true,
 *       "passed": false,
 *       "reason": "System prerequisite 'Disk Check' not met on node: agent-2",
 *       "rules": [ ... ]
 *     }
 *   ]
 * }
 * }</pre>
 */
@Extension
public class SystemPrerequisiteCheckAPI implements hudson.model.RootAction {

    private static final Logger LOGGER = Logger.getLogger(SystemPrerequisiteCheckAPI.class.getName());

    private static final String ISO_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "Node Prerequisites API";
    }

    @Override
    public String getUrlName() {
        return "node-prerequisites-api";
    }

    /**
     * GET endpoint: run system prerequisite checks on all nodes and return
     * results as JSON.
     *
     * @param req the Stapler request (supports {@code detailed=true})
     * @return JSON response with check results
     */
    public HttpResponse doCheckAllNodes(StaplerRequest req) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        boolean detailed = "true".equalsIgnoreCase(req.getParameter("detailed"));

        final JSONObject response = buildResponse(detailed);

        return new HttpResponse() {
            @Override
            public void generateResponse(StaplerRequest req, StaplerResponse rsp, Object node)
                    throws IOException, InterruptedException {
                rsp.setContentType("application/json; charset=UTF-8");
                rsp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
                rsp.getWriter().write(response.toString(2));
            }
        };
    }

    private JSONObject buildResponse(boolean detailed) {
        JSONObject response = new JSONObject();
        response.accumulate("timestamp", new SimpleDateFormat(ISO_FORMAT).format(new Date()));

        SystemPrerequisitesConfig config = SystemPrerequisitesConfig.get();
        if (config == null) {
            response.accumulate("error", "SystemPrerequisitesConfig not loaded");
            response.accumulate("totalNodes", 0);
            response.accumulate("passed", 0);
            response.accumulate("failed", 0);
            return response;
        }

        List<Node> nodes = new ArrayList<>(Jenkins.get().getNodes());
        Node builtIn = Jenkins.get();
        if (builtIn != null) {
            nodes.add(builtIn);
        }

        JSONArray nodeArray = new JSONArray();
        int passedCount = 0;
        int failedCount = 0;

        for (Node node : nodes) {
            JSONObject nodeResult = checkSingleNode(node, config, detailed);
            boolean passed = nodeResult.optBoolean("passed", false);
            if (passed) {
                passedCount++;
            } else {
                failedCount++;
            }
            nodeArray.add(nodeResult);
        }

        response.accumulate("totalNodes", nodes.size());
        response.accumulate("passed", passedCount);
        response.accumulate("failed", failedCount);
        response.accumulate("nodes", nodeArray);
        return response;
    }

    private JSONObject checkSingleNode(Node node, SystemPrerequisitesConfig config, boolean detailed) {
        JSONObject nodeResult = new JSONObject();

        String nodeName = node.getNodeName();
        if (nodeName == null || nodeName.isEmpty()) {
            nodeName = "Built-In";
        }
        nodeResult.accumulate("name", nodeName);

        Computer computer = node.toComputer();
        boolean online = computer != null && !computer.isOffline();
        nodeResult.accumulate("online", online);

        if (!online) {
            nodeResult.accumulate("passed", false);
            nodeResult.accumulate("reason", "Node is offline");
            if (detailed) {
                nodeResult.accumulate("rules", new JSONArray());
            }
            return nodeResult;
        }

        try {
            if (detailed) {
                JSONObject detail = config.checkNodeDetailed(node, "api-check");
                nodeResult.accumulate("passed", detail.optBoolean("passed", false));
                if (!detail.optBoolean("passed", false)) {
                    nodeResult.accumulate("reason", extractFirstFailure(detail));
                } else {
                    nodeResult.accumulate("reason", "");
                }
                nodeResult.accumulate("rules", detail.opt("rules"));
            } else {
                String reason = config.checkNode(node, "api-check");
                if (reason != null) {
                    nodeResult.accumulate("passed", false);
                    nodeResult.accumulate("reason", reason);
                } else {
                    nodeResult.accumulate("passed", true);
                    nodeResult.accumulate("reason", "");
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to check node {0}: {1}",
                    new Object[]{nodeName, e.getMessage()});
            nodeResult.accumulate("passed", false);
            nodeResult.accumulate("reason", "Check failed: " + e.getMessage());
            if (detailed) {
                nodeResult.accumulate("rules", new JSONArray());
            }
        }

        return nodeResult;
    }

    /**
     * Extract the first failure reason from a detailed check result.
     */
    private static String extractFirstFailure(JSONObject detail) {
        JSONArray rules = detail.optJSONArray("rules");
        if (rules == null) return "Unknown failure";

        for (int i = 0; i < rules.size(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            if (!rule.optBoolean("passed", true)) {
                JSONArray scripts = rule.optJSONArray("scripts");
                if (scripts != null) {
                    for (int j = 0; j < scripts.size(); j++) {
                        JSONObject script = scripts.getJSONObject(j);
                        if (!script.optBoolean("passed", true)) {
                            return script.optString("reason", "Script failed");
                        }
                    }
                }
                return "Rule '" + rule.optString("ruleName", "unknown") + "' failed";
            }
        }
        return "Unknown failure";
    }
}
