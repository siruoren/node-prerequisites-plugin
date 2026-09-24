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
 * Queue management: {@code maxConcurrentChecks} limits the number of
 * prerequisite checks (system + job level) running concurrently on a
 * <strong>single node</strong>; additional checks are queued in scheduling
 * order (FIFO) on that node until a slot frees up.
 * {@code maxConcurrentChecks <= 0} disables the limit. There is no global
 * cap across nodes.
 * <p>
 * Checks are strictly <strong>task-driven</strong>: {@link #canTake} is only
 * invoked by the Jenkins queue while a task is being scheduled onto a node,
 * so system-level checks (and their retries) never run without a pending
 * task. There is no periodic/background check; the only other trigger is the
 * admin REST API ({@code node-prerequisites-api/checkAllNodes}).
 * <p>
 * Pass cache: once the system-level checks pass for a task on a node, the
 * result is cached per task+node (invalidated on configuration change), so
 * job-level check retries do <strong>not</strong> re-run the system rules
 * &mdash; the execution order is always "all system checks, then job checks".
 * <p>
 * Same-node ordering: for each node, system-level checks run one at a time,
 * sequentially &mdash; including checks triggered by different queued tasks
 * &mdash; and a job-level check on that node only starts after <strong>all</strong>
 * system-level checks (every rule, from every queued task) have finished.
 * Checks on different nodes still run concurrently within the queue limit.
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
     * Per-node execution ordering state, keyed by node name ("Built-In" for
     * the controller). Guarantees, for each node: (1) all system-level checks
     * run one at a time, sequentially &mdash; including checks triggered by
     * different queued tasks; and (2) a job-level check on the node only
     * starts after every submitted system-level check (all rules, from all
     * queued tasks) has finished. The map is bounded by the number of
     * distinct node names, which is small on a Jenkins controller.
     */
    private static final class NodeQueue {
        /** system-level checks submitted and not yet finished on this node */
        int systemCount;
        /** serializes system-level check execution on this node (fair FIFO) */
        final Semaphore execution = new Semaphore(1, true);
        /**
         * Per-node concurrency limit for ALL checks on this node
         * ({@code maxConcurrentChecks}); created/recreated when the
         * configured limit changes. {@code null} = unlimited.
         */
        Semaphore concurrent;
        int concurrentLimit = -1;
    }

    private final Map<String, NodeQueue> nodeQueues = new HashMap<String, NodeQueue>();

    /**
     * System-check pass cache, keyed by "sys:itemId:nodeName" with the time of
     * the pass. Without it, every scheduling attempt &mdash; in particular
     * every job-level check retry of a task waiting in the queue &mdash; would
     * re-run ALL system rules, producing an interleaved
     * "system, job, system" execution order. Entries are dropped when the
     * configuration changes or they expire (30 minutes).
     */
    private final Map<String, Long> systemPassed = new HashMap<String, Long>();

    /** config version the pass cache was built against; cache cleared on change */
    private long seenConfigVersion = -1;

    @Override
    public CauseOfBlockage canTake(final Node node, Queue.BuildableItem item) {

        // Name of the queued task (job) this check is being run for, so that every
        // prerequisite execution log line can be traced back to its task in the Jenkins log.
        String taskName = (item.task != null && item.task.getName() != null)
                ? item.task.getName() : "<unknown>";

        // --- Phase 1: System-level checks (run first, on the node) ---
        // Retry count from the configuration; 0 (or less) means retry forever.
        // Once the system checks have passed for this task on this node, the
        // result is cached: while the JOB-level check retries (fixed infinite
        // retries, 60s), the system rules are NOT re-executed on every
        // scheduling attempt — the order stays "all system checks, then job
        // checks". The cache is invalidated when the configuration changes.
        String sysKey = "sys:" + key(item, node);
        clearSystemPassCacheIfConfigChanged();

        if (!systemPassed.containsKey(sysKey)) {
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
            // System check passed — cache it for this task+node so later
            // canTake calls (job-check retries) skip straight to Phase 2.
            cacheSystemPass(sysKey);
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
     * Record a system-check pass for the given task+node key. Occasionally
     * sweeps entries older than 30 minutes to bound the map size.
     */
    private void cacheSystemPass(String sysKey) {
        long now = System.currentTimeMillis();
        synchronized (systemPassed) {
            if (systemPassed.size() > 256) {
                java.util.Iterator<Map.Entry<String, Long>> it =
                        systemPassed.entrySet().iterator();
                while (it.hasNext()) {
                    if (now - it.next().getValue() > 30 * 60 * 1000L) {
                        it.remove();
                    }
                }
            }
            systemPassed.put(sysKey, now);
        }
    }

    /**
     * Drop the system-check pass cache when the configuration has changed
     * (version bump on every save of the System Prerequisites page), so rule
     * edits take effect on the next scheduling attempt.
     */
    private void clearSystemPassCacheIfConfigChanged() {
        SystemPrerequisitesConfig config = SystemPrerequisitesConfig.get();
        long v = config != null ? config.getConfigVersion() : 0L;
        if (v == seenConfigVersion) {
            return;
        }
        synchronized (this) {
            if (v != seenConfigVersion) {
                systemPassed.clear();
                seenConfigVersion = v;
                LOGGER.log(Level.INFO, "System prerequisite pass cache cleared (configuration changed)");
            }
        }
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
        final boolean system = "system".equals(label);
        final NodeQueue nq = nodeQueueFor(node);
        if (system) {
            // Register the system check on the node BEFORE the worker starts,
            // so a job-level check submitted later (or already waiting) on
            // this node cannot overtake it.
            synchronized (nq) {
                nq.systemCount++;
            }
        }
        Callable<CauseOfBlockage> callable = new Callable<CauseOfBlockage>() {
            public CauseOfBlockage call() throws Exception {
                if (system) {
                    return runWithPermits(task, label, node, taskName, nq, true);
                }
                // Job-level check: wait until NO system-level check is
                // pending or running on this node, then run. The wait loop
                // releases the monitor, so registrations/decrements proceed.
                synchronized (nq) {
                    while (nq.systemCount > 0) {
                        nq.wait();
                    }
                }
                return runWithPermits(task, label, node, taskName, nq, false);
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
     * Run a check with the per-node concurrency limit applied (if configured).
     * For system-level checks execution is additionally serialized per node
     * via {@link NodeQueue#execution}, and the node's pending count is
     * decremented (waking waiting job-level checks) when the check finishes,
     * whatever the outcome. The node execution semaphore is acquired BEFORE
     * the concurrency permit so a queued same-node check does not waste a
     * slot and block other checks on the node.
     */
    private CauseOfBlockage runWithPermits(CheckTask task, String label, Node node,
                                           String taskName, NodeQueue nq, boolean system) throws Exception {
        try {
            if (system) {
                nq.execution.acquire();
            }
            try {
                Semaphore concurrent = nodeConcurrency(nq);
                if (concurrent != null) {
                    try {
                        concurrent.acquire();
                    } catch (InterruptedException e) {
                        return CauseOfBlockage.fromMessage(
                                Messages._JobPrerequisitesChecker_FailedToCheckJobProrequisites(
                                        "interrupted while waiting in the prerequisite check queue"));
                    }
                }
                try {
                    return executeCheck(task, label, node, taskName);
                } finally {
                    if (concurrent != null) {
                        concurrent.release();
                    }
                }
            } finally {
                if (system) {
                    nq.execution.release();
                }
            }
        } finally {
            if (system) {
                synchronized (nq) {
                    nq.systemCount--;
                    nq.notifyAll();
                }
            }
        }
    }

    /**
     * Return the per-node concurrency semaphore for the configured
     * {@code maxConcurrentChecks} limit, recreating it when the limit
     * changes. {@code null} = unlimited (limit &lt;= 0).
     */
    private Semaphore nodeConcurrency(NodeQueue nq) {
        int limit = getConcurrentLimit();
        if (limit <= 0) {
            return null;
        }
        synchronized (nq) {
            if (nq.concurrent == null || nq.concurrentLimit != limit) {
                nq.concurrent = new Semaphore(limit, true);
                nq.concurrentLimit = limit;
                LOGGER.log(Level.INFO, "Per-node prerequisite check limit set to {0} concurrent checks", limit);
            }
            return nq.concurrent;
        }
    }

    private int getConcurrentLimit() {
        SystemPrerequisitesConfig config = SystemPrerequisitesConfig.get();
        return config != null ? config.getMaxConcurrentChecks() : 0;
    }

    /**
     * Return the per-node ordering state for the given node, creating it on
     * demand. Keyed by node name ("Built-In" for the controller) and never
     * removed; the map size is bounded by the number of distinct node names
     * ever seen, which is negligible on a controller.
     */
    private NodeQueue nodeQueueFor(Node node) {
        String name = node.getNodeName();
        if (name == null || name.isEmpty()) {
            name = "Built-In";
        }
        synchronized (nodeQueues) {
            NodeQueue nq = nodeQueues.get(name);
            if (nq == null) {
                nq = new NodeQueue();
                nodeQueues.put(name, nq);
            }
            return nq;
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
