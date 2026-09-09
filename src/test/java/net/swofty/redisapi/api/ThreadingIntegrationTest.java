package net.swofty.redisapi.api;

import net.swofty.redisapi.TestRedis;
import net.swofty.redisapi.api.requests.DataRequest;
import net.swofty.redisapi.api.requests.DataRequestResponder;
import net.swofty.redisapi.api.requests.DataResponse;
import net.swofty.redisapi.events.EventRegistry;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPubSub;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadingIntegrationTest {

    private static final long SLOW_MS = 2_000;

    @Test
    void slowHandlerOnOneChannelDoesNotStallAnotherChannel() throws Exception {
        TestRedis.requireOrSkip();
        String[] names = channelsOnDifferentStripes();
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowDone = new CountDownLatch(1);
        CountDownLatch fastReceived = new CountDownLatch(1);

        JedisPubSub previous = EventRegistry.pubSub;
        RedisAPI api = TestRedis.freshInstance("threading-handlers");
        api.registerChannel(names[0], e -> {
            slowStarted.countDown();
            sleep(SLOW_MS);
            slowDone.countDown();
        });
        api.registerChannel(names[1], e -> fastReceived.countDown());
        api.startListeners();
        TestRedis.awaitSubscribed(previous, ChannelRegistry.registeredChannels.size());

        try {
            api.publishMessage("all", ChannelRegistry.getFromName(names[0]), "slow").get(5, TimeUnit.SECONDS);
            assertTrue(slowStarted.await(5, TimeUnit.SECONDS));

            long start = System.nanoTime();
            api.publishMessage("all", ChannelRegistry.getFromName(names[1]), "fast").get(5, TimeUnit.SECONDS);
            boolean received = fastReceived.await(SLOW_MS / 2, TimeUnit.MILLISECONDS);
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            assertTrue(received, "message on an unrelated channel waited behind a slow handler (" + elapsed + "ms)");
        } finally {
            slowDone.await(5, TimeUnit.SECONDS);
            api.shutdown();
        }
    }

    @Test
    void throwingHandlerDoesNotKillTheSubscriber() throws Exception {
        TestRedis.requireOrSkip();
        String failing = "failing-" + UUID.randomUUID();
        String healthy = "healthy-" + UUID.randomUUID();
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch received = new CountDownLatch(1);

        JedisPubSub previous = EventRegistry.pubSub;
        RedisAPI api = TestRedis.freshInstance("threading-exceptions");
        api.registerChannel(failing, e -> {
            failed.countDown();
            throw new IllegalStateException("handler failure");
        });
        api.registerChannel(healthy, e -> received.countDown());
        api.startListeners();
        TestRedis.awaitSubscribed(previous, ChannelRegistry.registeredChannels.size());

        try {
            api.publishMessage("all", ChannelRegistry.getFromName(failing), "boom").get(5, TimeUnit.SECONDS);
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            sleep(250);

            api.publishMessage("all", ChannelRegistry.getFromName(healthy), "still alive").get(5, TimeUnit.SECONDS);
            assertTrue(received.await(3, TimeUnit.SECONDS), "subscriber stopped delivering after a handler threw");
        } finally {
            api.shutdown();
        }
    }

    @Test
    void slowResponderDoesNotStallOtherDataRequests() throws Exception {
        TestRedis.requireOrSkip();
        String slowKey = "slow-" + UUID.randomUUID();
        String fastKey = "fast-" + UUID.randomUUID();
        CountDownLatch slowStarted = new CountDownLatch(1);
        DataRequestResponder.create(slowKey, request -> {
            slowStarted.countDown();
            sleep(SLOW_MS);
            return new JSONObject().put("speed", "slow");
        });
        DataRequestResponder.create(fastKey, request -> new JSONObject().put("speed", "fast"));

        JedisPubSub previous = EventRegistry.pubSub;
        RedisAPI api = TestRedis.freshInstance("threading-responders");
        api.startListeners();
        TestRedis.awaitSubscribed(previous, ChannelRegistry.registeredChannels.size());

        try {
            CompletableFuture<DataResponse> slow = new DataRequest("threading-responders", slowKey, null).await();
            assertTrue(slowStarted.await(5, TimeUnit.SECONDS));

            DataResponse fast = new DataRequest("threading-responders", fastKey, null).await().get(5, TimeUnit.SECONDS);
            assertNotNull(fast.data(), "data request timed out behind an unrelated slow responder");

            slow.get(5, TimeUnit.SECONDS);
        } finally {
            api.shutdown();
        }
    }

    private static String[] channelsOnDifferentStripes() {
        String first = "slow-" + UUID.randomUUID();
        while (true) {
            String second = "fast-" + UUID.randomUUID();
            if (RedisExecutors.dispatchFor(first) != RedisExecutors.dispatchFor(second))
                return new String[]{first, second};
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
