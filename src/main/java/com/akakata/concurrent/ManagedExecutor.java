package com.akakata.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

/**
 * @author Kelvin
 */
class ManagedExecutor {

    private static final List<ExecutorService> EXECUTOR_SERVICES = new ArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (ExecutorService service : EXECUTOR_SERVICES) {
                service.shutdown();
            }
        }));
    }

    static ExecutorService newSingleThreadExecutor(ThreadFactory threadFactory) {
        final ExecutorService exec = new ScheduledThreadPoolExecutor(1, threadFactory);
        EXECUTOR_SERVICES.add(exec);
        return exec;
    }
}
