package net.swofty.redisapi.api;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void tasksRunOnNamedDaemonThreads() throws Exception {
        CompletableFuture<Thread> ran = new CompletableFuture<>();

        RedisExecutors.handlers().execute(() -> ran.complete(Thread.currentThread()));

        Thread thread = ran.get(5, TimeUnit.SECONDS);
        assertTrue(thread.getName().startsWith("atlas-redis-handler"), "task should run on the handler pool");
        assertTrue(thread.isDaemon(), "pool threads must not keep the JVM alive");
    }

    @Test
    void saturatedPoolRunsWorkOnTheCallerInsteadOfDropping() throws Exception {
        ExecutorService stripe = RedisExecutors.dispatchFor("saturation-" + UUID.randomUUID());
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        stripe.execute(() -> {
            blocked.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
            }
        });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 2_000; i++) {
            stripe.execute(() -> {
            });
        }

        CompletableFuture<Thread> overflow = new CompletableFuture<>();
        stripe.execute(() -> overflow.complete(Thread.currentThread()));
        release.countDown();

        assertSame(Thread.currentThread(), overflow.getNow(null),
                "work submitted to a full pool must run on the submitting thread, not be dropped");
    }

    @Test
    void shutDownPoolRunsWorkOnTheCallerAndFreshPoolsComeBack() throws Exception {
        ExecutorService old = RedisExecutors.handlers();

        RedisExecutors.shutdown();

        assertTrue(old.isShutdown());
        CompletableFuture<Thread> inline = new CompletableFuture<>();
        old.execute(() -> inline.complete(Thread.currentThread()));
        assertSame(Thread.currentThread(), inline.getNow(null),
                "work submitted to a shut down pool must run on the submitting thread, not be dropped");

        ExecutorService fresh = RedisExecutors.handlers();
        assertNotSame(old, fresh, "a new pool must be created after shutdown");
        CompletableFuture<String> ran = new CompletableFuture<>();
        fresh.execute(() -> ran.complete(Thread.currentThread().getName()));
        assertTrue(ran.get(5, TimeUnit.SECONDS).startsWith("atlas-redis-handler"));
    }
}
