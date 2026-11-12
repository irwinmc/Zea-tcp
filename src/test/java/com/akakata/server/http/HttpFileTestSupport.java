package com.akakata.server.http;

import io.netty.channel.embedded.EmbeddedChannel;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pumps embedded I/O callbacks while observing actual worker completion.
 */
final class HttpFileTestSupport {

    /**
     * Prevents construction of this test utility.
     */
    private HttpFileTestSupport() {
    }

    /**
     * Waits for a condition without sleeping or advancing virtual transfer deadlines.
     *
     * @param channel embedded I/O queue, or null for a worker-only observation
     * @param completed completion condition
     */
    static void await(EmbeddedChannel channel, BooleanSupplier completed) {
        long started = System.nanoTime();
        do {
            if (channel != null) {
                channel.runPendingTasks();
                channel.checkException();
            }
            if (completed.getAsBoolean()) {
                return;
            }
            Thread.yield();
        } while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
        assertTrue(completed.getAsBoolean(), "Filesystem work or ordered I/O completion did not finish");
    }
}
