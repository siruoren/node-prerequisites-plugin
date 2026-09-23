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
import hudson.matrix.MatrixConfiguration;
import hudson.model.AbstractProject;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Let job check if node matches its prerequisites just before starting a build on it.
 * <p>
 * Execution order: system-level rules run first, then job-level rules.
 * Both run <strong>on the target node</strong>:
 * <ul>
 *   <li>System-level: Groovy sandbox script sent to the agent via Remoting</li>
 *   <li>Job-level: Shell/Batch/Groovy script executed on the agent via {@code Launcher}</li>
 * </ul>
 * If system-level check blocks the node, job-level check is skipped.
 * <p>
 * Retry mechanism: when a check fails, it will be retried after a configurable
 * interval. Once a retry passes, the node is accepted and the queued task can
 * execute on it. After the maximum retry count is exceeded, the blockage
 * becomes permanent.
 */
@Extension
public class JobPrerequisitesChecker extends QueueTaskDispatcher {

    private static final Logger LOGGER = Logger.getLogger(JobPrerequisitesChecker.class.getName());

    ExecutorService pool = Executors.newCachedThreadPool();

    /** system-level check futures, keyed by "sys:itemId:nodeName" */
    private final Map<String, Future<CauseOfBlockage>> systemFutures = new HashMap<String, Future<CauseOfBlockage>>();

    /** job-level check futures, keyed by "job:itemId:nodeName" */
    private final Map<String, Future<CauseOfBlockage>> futures = new HashMap<String, Future<CauseOfBlockage>>();

    /** retry state per check key, tracks failures and retry timing */
    private final Map<String, RetryState> retryStates = new HashMap<String, RetryState>();

    @Override
    public CauseOfBlockage canTake(final Node node, Queue.BuildableItem item) {

        // --- Phase 1: System-level checks (run first, on the node) ---
        String sysKey = "sys:" + key(item, node);
        CauseOfBlockage sysBlockage = runWithRetry(sysKey, node, new CheckTask() {
            public CauseOfBlockage execute() throws Exception {
                SystemPrerequisitesConfig config = SystemPrerequisitesConfig.get();
                if (config == null) return null;
                String reason = config.checkNode(node);
                if (reason != null) {
                    return new BecauseSystemPrerequisitesArentMet(node, reason);
                }
                return null;
            }
        }, CHECKING_SYSTEM, "system");

        if (sysBlockage != null) {
            return sysBlockage;
        }

        // --- Phase 2: Job-level checks (run after system-level passes, on the node) ---
        final JobPrerequisites prerequisite = getPrerequisite(item);
        if (prerequisite == null) return null;

        String jobKey = "job:" + key(item, node);
        return runWithRetry(jobKey, node, new CheckTask() {
            public CauseOfBlockage execute() throws Exception {
                return prerequisite.check(node);
            }
        }, CHECKING_JOB, "job");
    }

    /**
     * Run a check with retry support.
     * <p>
     * Flow:
     * <ol>
     *   <li>If a future is in progress → return "checking" blockage</li>
     *   <li>If future completed with null (pass) → clear retry state, return null</li>
     *   <li>If future completed with blockage (fail):
     *     <ul>
     *       <li>If retries remaining and interval not elapsed → return "waiting for retry"</li>
     *       <li>If retries remaining and interval elapsed → submit new check, return "checking"</li>
     *       <li>If max retries exceeded → return permanent blockage</li>
     *     </ul>
     *   </li>
     * </ol>
     */
    private CauseOfBlockage runWithRetry(String checkKey, final Node node, final CheckTask task,
                                         CauseOfBlockage checkingMessage, String label) {

        int maxRetries = getRetryCount();
        long intervalMs = getRetryIntervalMillis();

        // Is there a future already in flight?
        Future<CauseOfBlockage> future = getFuture(checkKey);
        if (future != null) {
            if (!future.isDone()) {
                return checkingMessage;
            }
            // Future completed
            removeFuture(checkKey);
            try {
                CauseOfBlockage blockage = future.get();
                if (blockage == null) {
                    // Check passed — clear retry state
                    retryStates.remove(checkKey);
                    return null;
                }
                // Check failed
                RetryState state = retryStates.get(checkKey);
                if (state == null) {
                    state = new RetryState();
                    retryStates.put(checkKey, state);
                }
                state.lastBlockage = blockage;
                state.lastFailTime = System.currentTimeMillis();
                state.retryCount++;

                if (state.retryCount > maxRetries) {
                    // Max retries exceeded — permanent blockage
                    LOGGER.log(Level.INFO, "[{0}] Max retries ({1}) exceeded for {2}, blocking permanently: {3}",
                            new Object[]{label, maxRetries, node.getNodeName(), blockage.getClass().getSimpleName()});
                    return blockage;
                }

                LOGGER.log(Level.INFO, "[{0}] Check failed for {1}, retry {2}/{3} in {4}s",
                        new Object[]{label, node.getNodeName(), state.retryCount, maxRetries, intervalMs / 1000});
                // Return waiting-for-retry blockage; next canTake call will re-check after interval
                return CauseOfBlockage.fromMessage(
                        Messages._JobPrerequisitesChecker_WaitingForRetry(
                                label, state.retryCount, maxRetries, intervalMs / 1000));
            } catch (Exception e) {
                retryStates.remove(checkKey);
                if (label.equals("system")) {
                    return CauseOfBlockage.fromMessage(
                            Messages._JobPrerequisitesChecker_FailedToCheckSystemProrequisites(e.getMessage()));
                } else {
                    return CauseOfBlockage.fromMessage(
                            Messages._JobPrerequisitesChecker_FailedToCheckJobProrequisites(e.getMessage()));
                }
            }
        }

        // No future in flight. Check if we're in a retry-waiting state.
        RetryState state = retryStates.get(checkKey);
        if (state != null && state.lastBlockage != null) {
            long elapsed = System.currentTimeMillis() - state.lastFailTime;
            if (elapsed < intervalMs) {
                // Still within retry interval — keep waiting
                return CauseOfBlockage.fromMessage(
                        Messages._JobPrerequisitesChecker_WaitingForRetry(
                                label, state.retryCount, maxRetries,
                                (intervalMs - elapsed) / 1000));
            }
            // Interval elapsed — submit a new check
            if (state.retryCount > maxRetries) {
                return state.lastBlockage;
            }
        }

        // Submit a fresh check
        submitFuture(checkKey, task, label, node);
        return checkingMessage;
    }

    @SuppressWarnings("unchecked")
    private Future<CauseOfBlockage> getFuture(String checkKey) {
        if (checkKey.startsWith("sys:")) {
            return systemFutures.get(checkKey);
        }
        return futures.get(checkKey);
    }

    @SuppressWarnings("unchecked")
    private void removeFuture(String checkKey) {
        if (checkKey.startsWith("sys:")) {
            systemFutures.remove(checkKey);
        } else {
            futures.remove(checkKey);
        }
    }

    private void submitFuture(final String checkKey, final CheckTask task,
                              final String label, final Node node) {
        Callable<CauseOfBlockage> callable = new Callable<CauseOfBlockage>() {
            public CauseOfBlockage call() throws Exception {
                try {
                    return task.execute();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "[{0}] Check threw exception for {1}: {2}",
                            new Object[]{label, node.getNodeName(), e.getMessage()});
                    if (label.equals("system")) {
                        return CauseOfBlockage.fromMessage(
                                Messages._JobPrerequisitesChecker_FailedToCheckSystemProrequisites(e.getMessage()));
                    } else {
                        return CauseOfBlockage.fromMessage(
                                Messages._JobPrerequisitesChecker_FailedToCheckJobProrequisites(e.getMessage()));
                    }
                }
            }
        };
        Future<CauseOfBlockage> f = pool.submit(callable);
        if (checkKey.startsWith("sys:")) {
            systemFutures.put(checkKey, f);
        } else {
            futures.put(checkKey, f);
        }
    }

    private int getRetryCount() {
        SystemPrerequisitesConfig config = SystemPrerequisitesConfig.get();
        return config != null ? config.getRetryCount() : 3;
    }

    private long getRetryIntervalMillis() {
        SystemPrerequisitesConfig config = SystemPrerequisitesConfig.get();
        int seconds = config != null ? config.getRetryIntervalSeconds() : 30;
        return seconds * 1000L;
    }

    private final static CauseOfBlockage CHECKING_SYSTEM =
            CauseOfBlockage.fromMessage(Messages._JobPrerequisitesChecker_CheckingSystemPrerequisites());
    private final static CauseOfBlockage CHECKING_JOB =
            CauseOfBlockage.fromMessage(Messages._JobPrerequisitesChecker_CheckingJobPrerequisites());

    private String key(Queue.Item item, Node node) {
        return String.valueOf(item.id)+":"+node.getNodeName();
    }

    private JobPrerequisites getPrerequisite(Queue.BuildableItem item) {
        Queue.Task task = item.task;
        if (task instanceof AbstractProject) {
            AbstractProject<?,?> p = (AbstractProject<?,?>) task;
            if (task instanceof MatrixConfiguration) {
                p = (AbstractProject<?,?>)((MatrixConfiguration)task).getParent();
            }
            return p.getProperty(JobPrerequisites.class);
        }
        return null;
    }

    // --- Helper interfaces and classes ---

    private interface CheckTask {
        CauseOfBlockage execute() throws Exception;
    }

    /**
     * Tracks retry state for a single check key.
     */
    private static class RetryState {
        long lastFailTime;
        int retryCount;
        CauseOfBlockage lastBlockage;
    }


}
