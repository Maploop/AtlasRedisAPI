package net.swofty.redisapi.api;

import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Owns every worker thread the Redis layer uses. Queues are bounded and threads are named daemons
 * that exit after a minute idle. A pool that is saturated or already shut down never discards
 * work: the task runs on the thread that submitted it instead, so overload degrades into
 * back-pressure rather than silent loss.
 * <p>
 * Pools are created lazily on first use and torn down by {@link RedisAPI#shutdown()}. An instance
 * generated after that simply gets a fresh set on its first use.
 */
@ApiStatus.Internal
public final class RedisExecutors {
    private static final Logger LOGGER = Logger.getLogger(RedisExecutors.class.getName());

    private static final int QUEUE_CAPACITY = 2_000;
    private static final int DISPATCH_STRIPES = 8;
    private static final long WARN_INTERVAL_MS = 5_000;

    private static volatile Pools pools;

    private RedisExecutors() { }

    /**
     * The single-threaded stripe a channel's messages are handled on. Every message for a given
     * channel lands on the same stripe, which keeps per-channel FIFO ordering without serialising
     * every channel behind each other.
     *
     * @param channelName the name of the channel the message arrived on
     * @return the executor that channel's handlers run on
     */
    public static ExecutorService dispatchFor(String channelName) {
        return pools().dispatch[Math.floorMod(channelName.hashCode(), DISPATCH_STRIPES)];
    }

    /** Envelope work for the internal data request channel, which never blocks. */
    public static ExecutorService inbound() {
        return pools().inbound;
    }

    /** {@code DataRequestResponder} callbacks, which may block on IO. */
    public static ExecutorService handlers() {
        return pools().handlers;
    }

    /** Stages of the futures handed out by {@code DataRequest} and {@code publishMessage}. */
    public static ExecutorService completions() {
        return pools().completions;
    }

    /** Outbound publishes. */
    public static ExecutorService publisher() {
        return pools().publisher;
    }

    /**
     * Stops accepting new work on the current pools and lets queued work drain. Anything
     * submitted afterwards runs on the submitting thread until a later call creates fresh pools.
     */
    public static void shutdown() {
        Pools current;
        synchronized (RedisExecutors.class) {
            current = pools;
            pools = null;
        }
        if (current != null)
            current.shutdown();
    }

    private static Pools pools() {
        Pools current = pools;
        if (current == null) {
            synchronized (RedisExecutors.class) {
                current = pools;
                if (current == null)
                    pools = current = new Pools();
            }
        }
        return current;
    }

    private static final class Pools {
        final ExecutorService[] dispatch = new ExecutorService[DISPATCH_STRIPES];
        final ExecutorService inbound = pool("atlas-redis-inbound", 4);
        final ExecutorService handlers = pool("atlas-redis-handler", 32);
        final ExecutorService completions = pool("atlas-redis-completion", 16);
        final ExecutorService publisher = pool("atlas-redis-publisher", 24);

        Pools() {
            for (int i = 0; i < DISPATCH_STRIPES; i++)
                dispatch[i] = pool("atlas-redis-dispatch-" + i, 1);
        }

        void shutdown() {
            for (ExecutorService stripe : dispatch)
                stripe.shutdown();
            inbound.shutdown();
            handlers.shutdown();
            completions.shutdown();
            publisher.shutdown();
        }
    }

    private static ExecutorService pool(String name, int threads) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY), factory(name, threads > 1), runOnCaller(name));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static RejectedExecutionHandler runOnCaller(String name) {
        AtomicLong lastWarning = new AtomicLong();
        return (task, executor) -> {
            long now = System.currentTimeMillis();
            long last = lastWarning.get();
            if (now - last >= WARN_INTERVAL_MS && lastWarning.compareAndSet(last, now))
                LOGGER.warning("Redis pool " + name + " is " + (executor.isShutdown() ? "shut down" : "saturated")
                        + ", running work on the submitting thread instead.");
            task.run();
        };
    }

    private static ThreadFactory factory(String name, boolean numbered) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, numbered ? name + "-" + counter.incrementAndGet() : name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
