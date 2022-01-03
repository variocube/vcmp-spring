package com.variocube.vcmp;

import lombok.val;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Executor {

    private static Executor singleton;

    public static Executor getExecutor() {
        if (singleton == null) {
            singleton = new Executor();
        }
        return singleton;
    }

    private final ScheduledExecutorService scheduledThreadPool;
    private final AtomicInteger numWorkers = new AtomicInteger(0);

    private Executor() {
        this.scheduledThreadPool = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(), r -> {
            val thread = Executors.defaultThreadFactory().newThread(r);
            thread.setName(String.format("VCMP-Worker-%s", numWorkers.addAndGet(1)));
            return thread;
        });
    }

    public void submit(Runnable runnable) {
        this.scheduledThreadPool.submit(runnable);
    }

    public void schedule(Runnable runnable, long delay, TimeUnit timeUnit) {
        this.scheduledThreadPool.schedule(runnable, delay, timeUnit);
    }
}
