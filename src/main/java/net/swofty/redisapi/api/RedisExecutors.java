package net.swofty.redisapi.api;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Owns every thread the Redis layer uses. All queues are bounded and all threads are named
 * daemons, so a saturated pool degrades visibly instead of growing without limit.
 * <p>
 * The pools are process-wide and outlive any single {@link RedisAPI} instance, so
 * {@link RedisAPI#shutdown()} leaves them running. Call {@link #shutdown()} yourself when your
 * application is tearing down and wants the threads gone before the JVM exits.
 */
public final class RedisExecutors {
    private static final Logger LOGGER = Logger.getLogger(RedisExecutors.class.getName());

    private static final int QUEUE_CAPACITY = 2_000;
    private static final int DISPATCH_STRIPES = 8;

    private static final ExecutorService[] DISPATCH = new ExecutorService[DISPATCH_STRIPES];

    // Envelope work for the internal data request channel only, which never blocks.
    public static final ExecutorService INBOUND = pool("atlas-redis-inbound", 4, dropPolicy("inbound"));

    // Responder callbacks, which are allowed to stall as long as they'd like (under 8s)
    public static final ExecutorService HANDLERS = pool("atlas-redis-handler", 32, dropPolicy("handler"));

    /**
     * Stages of the futures handed out by {@code DataRequest}. Runs on the caller rather than
     * dropping, because a dropped stage would leave a caller's future incomplete forever. Safe
     * only because the subscriber thread never submits here.
     */
    public static final ExecutorService COMPLETIONS =
            pool("atlas-redis-completion", 16, new ThreadPoolExecutor.CallerRunsPolicy());

    // Publishes. Aborts rather than drops so the caller can fail its future.
    public static final ExecutorService PUBLISHER =
            pool("atlas-redis-publisher", 24, new ThreadPoolExecutor.AbortPolicy());

    public static final ScheduledExecutorService SCHEDULER = scheduler();

    static {
        for (int i = 0; i < DISPATCH_STRIPES; i++)
            DISPATCH[i] = pool("atlas-redis-dispatch-" + i, 1, dropPolicy("dispatch"));
    }

    private RedisExecutors() { }

    /**
     * The single-threaded stripe a channel's messages are handled on. Every message for a given
     * channel lands on the same stripe, which keeps per-channel FIFO ordering without serialising
     * unrelated channels behind each other.
     *
     * @param channelName the name of the channel the message arrived on
     * @return the executor that channel's handlers run on
     */
    public static ExecutorService dispatchFor(String channelName) {
        return DISPATCH[Math.floorMod(channelName.hashCode(), DISPATCH_STRIPES)];
    }

    public static void submit(ExecutorService executor, String what, Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
            LOGGER.warning("Redis " + what + " pool rejected a task.");
        }
    }

    /**
     * Stops every pool. Intended for application shutdown; the pools cannot be restarted
     * afterwards, so a {@link RedisAPI} generated after this call has nowhere to publish.
     */
    public static void shutdown() {
        for (ExecutorService stripe : DISPATCH)
            stripe.shutdownNow();

        INBOUND.shutdownNow();
        HANDLERS.shutdownNow();
        COMPLETIONS.shutdownNow();
        PUBLISHER.shutdownNow();
        SCHEDULER.shutdownNow();
    }

    private static ExecutorService pool(String name, int threads, RejectedExecutionHandler policy) {
        return new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY), factory(name, threads > 1), policy);
    }

    private static ScheduledExecutorService scheduler() {
        ScheduledThreadPoolExecutor executor =
                new ScheduledThreadPoolExecutor(4, factory("atlas-redis-scheduler", true));
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static RejectedExecutionHandler dropPolicy(String label) {
        return (task, executor) -> LOGGER.warning("Redis " + label + " pool is saturated, dropped a task.");
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
