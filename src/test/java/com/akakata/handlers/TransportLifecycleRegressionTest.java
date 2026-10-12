package com.akakata.handlers;

import com.akakata.server.ConnectionAdmission;
import com.akakata.server.TransportLimits;
import com.akakata.util.NettyUtils;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies pre-authentication socket limits and persistent login/idle deadlines.
 */
class TransportLifecycleRegressionTest {

    /**
     * Creates the lifetime fixture.
     */
    TransportLifecycleRegressionTest() {}

    /**
     * Returns rejected, timed-out and closed socket slots exactly once.
     */
    @Test
    void unauthenticatedConnectionsShareAnAtomicBudgetAndReturnSlots() {
        ConnectionAdmission admission = new ConnectionAdmission(new TransportLimits(1, 100, 0, 1024));
        EmbeddedChannel first = new EmbeddedChannel(new TransportLifecycleHandler(admission));
        EmbeddedChannel rejected = new EmbeddedChannel(new TransportLifecycleHandler(admission));
        try {
            assertEquals(1, admission.activeConnections());
            assertFalse(rejected.isOpen());
            first.advanceTimeBy(101, TimeUnit.MILLISECONDS); first.runScheduledPendingTasks();
            assertFalse(first.isOpen());
            assertEquals(0, admission.activeConnections());
            EmbeddedChannel replacement = new EmbeddedChannel(new TransportLifecycleHandler(admission));
            try {
                assertTrue(replacement.isActive());
                assertEquals(1, admission.activeConnections());
                replacement.close(); replacement.close();
                assertEquals(0, admission.activeConnections());
            } finally { replacement.finishAndReleaseAll(); }
        } finally { first.finishAndReleaseAll(); rejected.finishAndReleaseAll(); }
    }

    /**
     * Cancels login expiry while preserving idle detection across pipeline replacement.
     */
    @Test
    void authenticatedPipelineKeepsIdleGuardAndCancelsLoginDeadline() {
        ConnectionAdmission admission = new ConnectionAdmission(new TransportLimits(1, 100, 200, 1024));
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            TransportLifecycleHandler.install(channel.pipeline(), admission);
            channel.pipeline().addLast("oldCodec", new ChannelInboundHandlerAdapter());
            // Installing after activation requires forwarding activation to newly added handlers.
            channel.pipeline().fireChannelActive();
            NettyUtils.clearPipeline(channel.pipeline());
            assertNotNull(channel.pipeline().get(IdleStateHandler.class));
            assertNotNull(channel.pipeline().get(TransportLifecycleHandler.class));
            assertNull(channel.pipeline().get("oldCodec"));
            TransportLifecycleHandler.authenticated(channel);
            channel.advanceTimeBy(101, TimeUnit.MILLISECONDS); channel.runScheduledPendingTasks();
            assertTrue(channel.isActive());
            channel.pipeline().fireUserEventTriggered(io.netty.handler.timeout.IdleStateEvent.READER_IDLE_STATE_EVENT);
            assertFalse(channel.isOpen());
            assertEquals(0, admission.activeConnections());
        } finally { channel.finishAndReleaseAll(); }
    }

    /**
     * Rejects unsupported frame and deadline configurations before opening sockets.
     */
    @Test
    void invalidTransportPoliciesFailFast() {
        assertThrows(IllegalArgumentException.class, () -> new TransportLimits(0, 100, 0, 1024));
        assertThrows(IllegalArgumentException.class, () -> new TransportLimits(1, 0, 0, 1024));
        assertThrows(IllegalArgumentException.class, () -> new TransportLimits(1, 100, -1, 1024));
        assertThrows(IllegalArgumentException.class, () -> new TransportLimits(1, 100, 0, 65536));
    }
}
