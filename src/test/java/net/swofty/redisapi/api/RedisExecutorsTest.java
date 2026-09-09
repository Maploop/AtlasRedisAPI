package net.swofty.redisapi.api;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Note that nothing here calls {@link RedisExecutors#shutdown()} - the pools are process-wide,
 * so shutting them down would break every other test sharing this JVM.
 */
class RedisExecutorsTest {

    @Test
    void dispatchForIsStableForTheSameChannel() {
        assertSame(RedisExecutors.dispatchFor("cove"), RedisExecutors.dispatchFor("cove"),
                "a channel must always be handled on the same stripe to keep FIFO ordering");
    }

    @Test
    void dispatchForSpreadsChannelsAcrossStripes() {
        Set<ExecutorService> stripes = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            stripes.add(RedisExecutors.dispatchFor("channel-" + i));
        }

        assertTrue(stripes.size() > 1, "channels must not all land on one stripe, got " + stripes.size());
    }

    @Test
    void dispatchForHandlesNegativeHashCodes() {
        // "polygenelubricants".hashCode() is Integer.MIN_VALUE, which a plain % would leave negative
        assertDoesNotThrow(() -> RedisExecutors.dispatchFor("polygenelubricants"));
    }

    @Test
    void submitRunsTheTask() throws Exception {
        CompletableFuture<String> ran = new CompletableFuture<>();

        RedisExecutors.submit(RedisExecutors.HANDLERS, "handler", () -> ran.complete(Thread.currentThread().getName()));

        assertTrue(ran.get(5, TimeUnit.SECONDS).startsWith("atlas-redis-handler"),
                "task should run on the handler pool");
    }

    @Test
    void submitSwallowsRejection() {
        // A stopped pool rejects everything, same as a saturated one with an abort policy
        ExecutorService rejecting = Executors.newSingleThreadExecutor();
        rejecting.shutdown();

        assertDoesNotThrow(() -> RedisExecutors.submit(rejecting, "test", () -> {
        }), "a rejection must never escape into the subscriber thread");
    }

    @Test
    void poolThreadsAreDaemons() throws Exception {
        CompletableFuture<Boolean> daemon = new CompletableFuture<>();

        RedisExecutors.INBOUND.execute(() -> daemon.complete(Thread.currentThread().isDaemon()));

        assertEquals(true, daemon.get(5, TimeUnit.SECONDS), "pool threads must not keep the JVM alive");
    }

    @Test
    void schedulerRunsDelayedTasks() throws Exception {
        CompletableFuture<Long> fired = new CompletableFuture<>();
        long start = System.nanoTime();

        RedisExecutors.SCHEDULER.schedule(
                () -> fired.complete(System.nanoTime() - start), 50, TimeUnit.MILLISECONDS);

        assertTrue(fired.get(5, TimeUnit.SECONDS) >= TimeUnit.MILLISECONDS.toNanos(40),
                "scheduled task fired too early");
    }
}
