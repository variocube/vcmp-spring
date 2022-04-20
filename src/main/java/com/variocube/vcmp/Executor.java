package com.variocube.vcmp;

import lombok.val;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages execution of tasks.
 *
 * Uses a cached thread pool to asynchronously execute tasks and a scheduled thread pool to schedule tasks.
 * Once a scheduled task is due, it dispatches it to the cached thread pool.
 */
public class Executor {

    private static Executor singleton;

    public static Executor getExecutor() {
        if (singleton == null) {
            singleton = new Executor();
        }
        return singleton;
    }

    private final ScheduledExecutorService scheduledThreadPool;
    private final ExecutorService cachedThreadPool;
    private final AtomicInteger numSchedulers = new AtomicInteger(0);
    private final AtomicInteger numWorkers = new AtomicInteger(0);

    private Executor() {
        this.scheduledThreadPool = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(), this::schedulerThreadFactory);
        this.cachedThreadPool = Executors.newCachedThreadPool(this::workerThreadFactory);
    }

    private Thread schedulerThreadFactory(Runnable r) {
        val thread = Executors.defaultThreadFactory().newThread(r);
        thread.setName(String.format("VCMP-Scheduler-%s", numSchedulers.addAndGet(1)));
        return thread;
    }

    private Thread workerThreadFactory(Runnable r) {
        val thread = Executors.defaultThreadFactory().newThread(r);
        thread.setName(String.format("VCMP-Worker-%s", numWorkers.addAndGet(1)));
        return thread;
    }

    public void submit(Runnable runnable) {
        this.cachedThreadPool.submit(runnable);
    }

    public void schedule(Runnable runnable, long delay, TimeUnit timeUnit) {
        // Submit the task to the cached thread pool once it's due.
        // We don't directly run the task on the scheduled thread pool, because it does
        // not allocate additional threads. Therefore, long-running tasks would
        // interfere with scheduling.
        this.scheduledThreadPool.schedule(() -> submit(runnable), delay, timeUnit);
    }

}
