package org.mule.extension.agent.composer.internal.tracing;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExecutionCancellationRegistryTest {

    @Test
    public void interruptsRegisteredExecutionAndRemovesIt() throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            ExecutionCancellationRegistry.register("execution-1");
            registered.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException expected) {
                interrupted.countDown();
            } finally {
                ExecutionCancellationRegistry.unregister("execution-1");
            }
        });
        worker.start();
        assertTrue(registered.await(2, TimeUnit.SECONDS));
        assertTrue(ExecutionCancellationRegistry.isRunning("execution-1"));
        assertTrue(ExecutionCancellationRegistry.cancel("execution-1"));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        worker.join(2_000);
        assertFalse(ExecutionCancellationRegistry.isRunning("execution-1"));
    }
}
