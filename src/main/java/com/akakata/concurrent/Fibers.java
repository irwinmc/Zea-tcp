package com.akakata.concurrent;

import org.jetlang.fibers.Fiber;
import org.jetlang.fibers.PoolFiberFactory;
import org.jetlang.fibers.ThreadFiber;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * @author Kelvin
 */
public class Fibers {

    // TODO inject this from spring or AppContext

    private static final ExecutorService EXECUTOR;
    private static final PoolFiberFactory FACT;
    private static final ConcurrentHashMap<Lane<String, ExecutorService>, PoolFiberFactory> LANE_POOL_FACTORY_MAP =
            new ConcurrentHashMap<>();

    static {
        EXECUTOR = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("fiber-pool"));
        FACT = new PoolFiberFactory(EXECUTOR);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                EXECUTOR.shutdown();
            }
        });
    }

    /**
     * Creates and starts a fiber and returns the created instance.
     *
     * @return The created fiber.
     */
    public static Fiber pooledFiber() {
        Fiber fiber = FACT.create();
        fiber.start();
        return fiber;
    }

    /**
     * Creates and starts a fiber and returns the created instance.
     */
    public static Fiber pooledFiber(Lane<String, ExecutorService> lane) {
        if (LANE_POOL_FACTORY_MAP.get(lane) == null) {
            LANE_POOL_FACTORY_MAP.putIfAbsent(lane, new PoolFiberFactory(lane.getUnderlyingLane()));
        }

        Fiber fiber = LANE_POOL_FACTORY_MAP.get(lane).create();
        fiber.start();
        return fiber;
    }

    public static Fiber threadFiber() {
        Fiber fiber = new ThreadFiber();
        fiber.start();
        return fiber;
    }
}
