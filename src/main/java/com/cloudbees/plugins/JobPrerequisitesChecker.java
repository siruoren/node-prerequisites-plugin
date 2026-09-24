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
import hudson.matrix.MatrixConfiguration;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Let job check if node matches its prerequisites just before starting a build on it.
 * <p>
 * Execution order: system-level rules run first, then job-level rules.
 * Both run <strong>on the target node</strong>:
 * <ul>
 *   <li>System-level: Groovy sandbox script sent to the agent via Remoting</li>
 *   <li>Job-level: Shell/Batch/Groovy script executed on the agent via {@code Launcher}
 *       (Groovy runs in the sandbox over Remoting, no {@code groovy} CLI required)</li>
 * </ul>
 * If system-level check blocks the node, job-level check is skipped.
 * <p>
 * Retry mechanism:
 * <ul>
 *   <li>System-level checks: retried up to the configured retry count
 *       ({@code 0} means retry forever) with the configured interval.</li>
 *   <li>Job-level checks: always retried forever with a fixed 60 second interval.</li>
 * </ul>
 * <p>
 * Queue management: all prerequisite checks (system + job level) share a single
 * queue. At most {@code maxConcurrentChecks} checks run concurrently; additional
 * checks are queued in scheduling order (FIFO) until a slot frees up.
 * {@code maxConcurrentChecks <= 0} disables the limit.
 * <p>
 * Same-node serialization: multiple system-level rules matching the same node
 * &mdash; including checks triggered by different queued tasks &mdash; run
 * <strong>sequentially</strong> on that node, one at a time in scheduling
 * order (per-node lock held while the check runs). Checks on different nodes
 * still run concurrently within the queue limit; job-level checks are
 * unaffected.
 */
@Extension
public class JobPrerequisitesChecker extends QueueTaskDispatcher {

    private static final Logger LOGGER = Logger.getLogger(JobPrerequisitesChecker.class.getName());

    /** fixed retry interval for job-level prerequisite checks (seconds) */
    private static final long JOB_RETRY_INTERVAL_MS = SystemPrerequisitesData.JOB_RETRY_INTERVAL_SECONDS * 1000L;

    ExecutorService pool = Executors.newCachedThreadPool();

    /** system-level check futures, keyed by "sys:itemId:nodeName" */
    private final Map<String, Future<CauseOfBlockage>> systemFutures = new HashMap<String, Future<CauseOfBlockage>>();

    /** job-level check futures, keyed by "job:itemId:nodeName" */
    private final Map<String, Future<CauseOfBlockage>> futures = new HashMap<String, Future<CauseOfBlockage>>();

    /** retry state per check key, tracks failures and retry timing */
    private final Map<String, RetryState> retryStates = new HashMap<String, RetryState>();

    /**
     * Per-node serialization locks for system-level checks: system checks that
     * involve the same node (even when triggered by different queued tasks)
     * run one at a time, in scheduling order. Keyed by node name
     * ("Built-In" for the controller); the map is bounded by the number of
     * distinct node names, which is small on a Jenkins controller.
     */
    private final Map<String, Object> nodeLocks = new HashMap<String, Object>();

    /**
     * Fair FIFO semaphore limiting concurrent prerequisite checks.
     * Recreated whenever the configured limit changes; {@code null} = unlimited.
     */
    private volatile Semaphore checkPermits;
    private volatile int permitsLimit = -1;

    @Override
    public CauseOfBlockage canTake(final Node node, Queue.BuildableItem item) {

        // Name of the queued task (job) this check is being run for, so that every
        // prerequisite execution log line can be traced back to its task in the Jenkins log.
        String taskName = (item.task != null && item.task.getName() != null)
                ? item.task.getName() : "<unknown>";

        // --- Phase 1: System-level checks (run first, on the node) ---
        // Retry count from the configuration; 0 (or less) means retry forever.
        String sysKey = "sys:" + key(item, node);
        CauseOfBlockage sysBlockage = runWithRetry(sysKey, node, new CheckTask() {
            public CauseOfBlockage execute(String taskName) throws Exception {
                SystemPrerequisitesConfig config = SystemPrerequisitesConfig.get();
                if (config == null) return null;
                String reason = config.checkNode(node, taskName);
                if (reason != null) {
                    return new BecauseSystemPrerequisitesArentMet(node, reason);
                }
                return null;
            }
        }, CHECKING_SYSTEM, "system", taskName, getRetryCount(), getRetryIntervalMillis());

        if (sysBlockage != null) {
            return sysBlockage;
        }

        // --- Phase 2: Job-level checks (run after system-level passes, on the node) ---
        // Job-level checks retry forever with a fixed 60 second interval.
        final JobPrerequisites prerequisite = getPrerequisite(item);
        if (prerequisite == null) return null;

        String jobKey = "job:" + key(item, node);
        return runWithRetry(jobKey, node, new CheckTask() {
            public CauseOfBlockage execute(String taskName) throws Exception {
                return prerequisite.check(node, taskName);
            }
        }, CHECKING_JOB, "job", taskName, 0, JOB_RETRY_INTERVAL_MS);
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
     *       <li>If max retries exceeded (only possible for a positive {@code maxRetries}) → return permanent blockage</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param maxRetries maximum number of retries; {@code <= 0} means retry forever
     * @param intervalMs delay between retries in milliseconds
     * @param taskName name of the queued task (job) this check is for, included in logs
     */
    private CauseOfBlockage runWithRetry(String checkKey, final Node node, final CheckTask task,
                                         CauseOfBlockage checkingMessage, String label, String taskName,
                                         final int maxRetries, final long intervalMs) {

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

                if (maxRetries > 0 && state.retryCount > maxRetries) {
                    // Max retries exceeded — permanent blockage
                    LOGGER.log(Level.INFO, "[{0}] Max retries ({1}) exceeded for task {2} on node {3}, blocking permanently: {4}",
                            new Object[]{label, maxRetries, taskName, node.getNodeName(), blockage.getClass().getSimpleName()});
                    return blockage;
                }

                if (maxRetries > 0) {
                    LOGGER.log(Level.INFO, "[{0}] Check failed for task {1} on node {2}, retry {3}/{4} in {5}s",
                            new Object[]{label, taskName, node.getNodeName(), state.retryCount, maxRetries, intervalMs / 1000});
                    // Return waiting-for-retry blockage; next canTake call will re-check after interval
                    return CauseOfBlockage.fromMessage(
                            Messages._JobPrerequisitesChecker_WaitingForRetry(
                                    label, state.retryCount, maxRetries, intervalMs / 1000));
                } else {
                    LOGGER.log(Level.INFO, "[{0}] Check failed for task {1} on node {2}, retry {3}/\u221E (no limit) in {4}s",
                            new Object[]{label, taskName, node.getNodeName(), state.retryCount, intervalMs / 1000});
                    return CauseOfBlockage.fromMessage(
                            Messages._JobPrerequisitesChecker_WaitingForRetryInfinite(
                                    label, state.retryCount, intervalMs / 1000));
                }
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
                if (maxRetries > 0) {
                    return CauseOfBlockage.fromMessage(
                            Messages._JobPrerequisitesChecker_WaitingForRetry(
                                    label, state.retryCount, maxRetries,
                                    (intervalMs - elapsed) / 1000));
                } else {
                    return CauseOfBlockage.fromMessage(
                            Messages._JobPrerequisitesChecker_WaitingForRetryInfinite(
                                    label, state.retryCount, (intervalMs - elapsed) / 1000));
                }
            }
            // Interval elapsed — submit a new check
            if (maxRetries > 0 && state.retryCount > maxRetries) {
                return state.lastBlockage;
            }
        }

        // Submit a fresh check
        submitFuture(checkKey, task, label, node, taskName);
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
                              final String label, final Node node, final String taskName) {
        final Semaphore permits = checkPermits();
        // System-level checks on the SAME node are serialized across queued
        // items: the node lock is acquired BEFORE the global queue permit, so
        // multiple system checks involving one node run one after another in
        // scheduling order while checks on other nodes stay concurrent.
        final Object nodeLock = "system".equals(label) ? nodeLockFor(node) : null;
        Callable<CauseOfBlockage> callable = new Callable<CauseOfBlockage>() {
            public CauseOfBlockage call() throws Exception {
                if (nodeLock != null) {
                    synchronized (nodeLock) {
                        return runWithPermits(permits, task, label, node, taskName);
                    }
                }
                return runWithPermits(permits, task, label, node, taskName);
            }
        };
        Future<CauseOfBlockage> f = pool.submit(callable);
        if (checkKey.startsWith("sys:")) {
            systemFutures.put(checkKey, f);
        } else {
            futures.put(checkKey, f);
        }
    }

    /**
     * Acquire a slot from the global concurrency queue (if enabled), run the
     * check, and release the slot. Called while holding the per-node lock for
     * system-level checks.
     */
    private CauseOfBlockage runWithPermits(Semaphore permits, CheckTask task,
                                           String label, Node node, String taskName) throws Exception {
        if (permits != null) {
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                return CauseOfBlockage.fromMessage(
                        Messages._JobPrerequisitesChecker_FailedToCheckJobProrequisites(
                                "interrupted while waiting in the prerequisite check queue"));
            }
            try {
                return executeCheck(task, label, node, taskName);
            } finally {
                permits.release();
            }
        }
        return executeCheck(task, label, node, taskName);
    }

    /**
     * Return the serialization lock for system-level checks on the given node,
     * creating it on demand. Locks are keyed by node name ("Built-In" for the
     * controller) and never removed; the map size is bounded by the number of
     * distinct node names ever seen, which is negligible on a controller.
     */
    private Object nodeLockFor(Node node) {
        String name = node.getNodeName();
        if (name == null || name.isEmpty()) {
            name = "Built-In";
        }
        synchronized (nodeLocks) {
            Object lock = nodeLocks.get(name);
            if (lock == null) {
                lock = new Object();
                nodeLocks.put(name, lock);
            }
            return lock;
        }
    }

    private CauseOfBlockage executeCheck(CheckTask task, String label, Node node, String taskName) {
        try {
            return task.execute(taskName);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[{0}] Check threw exception for task {1} on node {2}: {3}",
                    new Object[]{label, taskName, node.getNodeName(), e.getMessage()});
            if (label.equals("system")) {
                return CauseOfBlockage.fromMessage(
                        Messages._JobPrerequisitesChecker_FailedToCheckSystemProrequisites(e.getMessage()));
            } else {
                return CauseOfBlockage.fromMessage(
                        Messages._JobPrerequisitesChecker_FailedToCheckJobProrequisites(e.getMessage()));
            }
        }
    }

    /**
     * Returns the fair FIFO semaphore that caps concurrent prerequisite checks,
     * recreating it when the configured limit changes. {@code null} = unlimited.
     */
    private Semaphore checkPermits() {
        SystemPrerequisitesConfig config = SystemPrerequisitesConfig.get();
        int limit = config != null ? config.getMaxConcurrentChecks() : 0;
        if (limit <= 0) {
            return null;
        }
        Semaphore s = checkPermits;
        if (s != null && permitsLimit == limit) {
            return s;
        }
        synchronized (this) {
            if (checkPermits == null || permitsLimit != limit) {
                checkPermits = new Semaphore(limit, true);
                permitsLimit = limit;
                LOGGER.log(Level.INFO, "Prerequisite check queue limit set to {0} concurrent checks", limit);
            }
            return checkPermits;
        }
    }

    private int getRetryCount() {
        SystemPrerequisitesConfig config = SystemPrerequisitesConfig.get();
        return config != null ? config.getRetryCount() : 0;
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
        return String.valueOf(item.getId())+":"+node.getNodeName();
    }

    /**
     * Look up the job-level prerequisite property of the queued task.
     * Works for every {@link Job} type (freestyle, matrix, pipeline, ...).
     */
    private JobPrerequisites getPrerequisite(Queue.BuildableItem item) {
        Queue.Task task = item.task;
        if (task instanceof Job) {
            Job<?,?> p = (Job<?,?>) task;
            if (task instanceof MatrixConfiguration) {
                p = (Job<?,?>)((MatrixConfiguration)task).getParent();
            }
            return p.getProperty(JobPrerequisites.class);
        }
        return null;
    }

    // --- Helper interfaces and classes ---

    private interface CheckTask {
        CauseOfBlockage execute(String taskName) throws Exception;
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
