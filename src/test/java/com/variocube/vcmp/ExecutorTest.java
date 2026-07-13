package com.variocube.vcmp;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorTest {

    @Test
    void workerThreadsAreDaemon() throws Exception {
        CompletableFuture<Thread> thread = new CompletableFuture<>();
        Executor.getExecutor().submit(() -> thread.complete(Thread.currentThread()));

        assertThat(thread.get(3, TimeUnit.SECONDS).isDaemon())
                .as("VCMP worker threads must be daemon, they must not keep a JVM alive (center#427)")
                .isTrue();
    }

    @Test
    void schedulerDispatchesToDaemonWorker() throws Exception {
        CompletableFuture<Thread> thread = new CompletableFuture<>();
        Executor.getExecutor().schedule(() -> thread.complete(Thread.currentThread()), 1, TimeUnit.MILLISECONDS);

        assertThat(thread.get(3, TimeUnit.SECONDS).isDaemon()).isTrue();
    }
}
