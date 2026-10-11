package com.akakata.handlers;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.app.impl.DefaultGame;
import com.akakata.event.Events;
import com.akakata.event.SessionEventHandler;
import com.akakata.protocols.Protocol;
import com.akakata.service.LoginService;
import com.akakata.service.SecurityService;
import com.akakata.service.SessionService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies delayed TCP and WebSocket login callbacks and channel-disconnect cleanup.
 */
@Timeout(10)
class LoginCallbackLifecycleRegressionTest {

    /**
     * Selects the TCP or WebSocket login path under test.
     */
    enum Transport {

        /**
         * Tcp used by this regression fixture.
         */
        TCP,

        /**
         * Websocket used by this regression fixture.
         */
        WEBSOCKET
    }

    /**
     * Creates a login callback lifecycle regression test instance.
     */
    LoginCallbackLifecycleRegressionTest() {
    }

    /**
     * Verifies the regression scenario: delayed success cannot revive closed session.
     *
     * @param transport the transport
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void delayedSuccessCannotReviveClosedSession(Transport transport) {
        try (Fixture fixture = new Fixture(transport)) {
            fixture.startLogin();
            fixture.session.close();

            fixture.write.complete();
            fixture.channel.runPendingTasks();

            assertFalse(fixture.channel.isOpen());
            assertEquals(Session.Status.CLOSED, fixture.session.getStatus());
            assertEquals(0, fixture.protocol.applications.get());
            assertTrue(fixture.game.getSessions().isEmpty());
            assertTrue(fixture.session.getEventHandlers(Events.ANY).stream().noneMatch(handler ->
                    handler instanceof SessionEventHandler listener && listener.getSession() == fixture.session));
        }
    }

    /**
     * Verifies the regression scenario: room shutdown rejects delayed login before installing
     * protocol.
     *
     * @param transport the transport
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void roomShutdownRejectsDelayedLoginBeforeInstallingProtocol(Transport transport) {
        try (Fixture fixture = new Fixture(transport)) {
            fixture.startLogin();
            fixture.game.close();

            fixture.write.complete();
            fixture.channel.runPendingTasks();

            assertFalse(fixture.channel.isOpen());
            assertEquals(Session.Status.CLOSED, fixture.session.getStatus());
            assertTrue(fixture.game.getSessions().isEmpty());
        }
    }

    /**
     * Verifies the regression scenario: closing channel during login releases pending session.
     *
     * @param transport the transport
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void closingChannelDuringLoginReleasesPendingSession(Transport transport) {
        try (Fixture fixture = new Fixture(transport)) {
            fixture.startLogin();

            fixture.channel.close();
            fixture.channel.runPendingTasks();

            assertEquals(Session.Status.CLOSED, fixture.session.getStatus());
            assertEquals(0, fixture.protocol.applications.get());
        }
    }

    /**
     * Verifies the regression scenario: successful login and channel close release session.
     *
     * @param transport the transport
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void successfulLoginAndChannelCloseReleaseSession(Transport transport) {
        try (Fixture fixture = new Fixture(transport)) {
            fixture.startLogin();
            fixture.write.complete();
            fixture.channel.runPendingTasks();
            assertTrue(fixture.session.isConnected());
            assertEquals(1, fixture.protocol.applications.get());
            assertTrue(fixture.game.getSessions().contains(fixture.session));

            fixture.channel.close();
            fixture.channel.runPendingTasks();

            assertEquals(Session.Status.CLOSED, fixture.session.getStatus());
            assertTrue(fixture.game.getSessions().isEmpty());
        }
    }

    /**
     * Verifies the regression scenario: rejected tcp login releases payload.
     */
    @Test
    void rejectedTcpLoginReleasesPayload() {
        try (Fixture fixture = new Fixture(Transport.TCP)) {
            ByteBuf payload = Unpooled.buffer().writeByte(1);
            fixture.channel.writeInbound(Events.event(payload, Events.SESSION_MESSAGE));

            assertEquals(0, payload.refCnt());
        }
    }

    /**
     * Owns channel, session and protocol resources for a login regression scenario.
     */
    private static final class Fixture implements AutoCloseable {

        /**
         * Game used by this regression fixture.
         */
        final DefaultGame game = new DefaultGame();

        /**
         * Session used by this regression fixture.
         */
        final PlayerSession session = game.createPlayerSession();

        /**
         * Sessions used by this regression fixture.
         */
        final SessionService sessions = new SessionService(buffer -> null, false);

        /**
         * Protocol used by this regression fixture.
         */
        final RecordingProtocol protocol = new RecordingProtocol();

        /**
         * Write used by this regression fixture.
         */
        final DelayedWrite write = new DelayedWrite();

        /**
         * Associated transport channel.
         */
        final EmbeddedChannel channel;

        /**
         * Transport used by this regression fixture.
         */
        final Transport transport;

        /**
         * Creates a fixture instance with the supplied dependencies.
         *
         * @param transport the transport
         */
        Fixture(Transport transport) {
            this.transport = transport;
            LoginService login = new LoginService(sessions, new SecurityService()) {
                @Override
                public LoginResult login(ByteBuf buffer, Game game) {
                    return new LoginResult(session, "test-token");
                }
            };
            channel = new EmbeddedChannel(write, transport == Transport.TCP
                    ? new LoginHandler(protocol, game, login)
                    : new WebSocketLoginHandler(protocol, game, login));
        }

        /**
         * Verifies the regression scenario: start login.
         */
        void startLogin() {
            channel.writeInbound(transport == Transport.TCP
                    ? Events.event(Unpooled.buffer().writeByte(1), Events.LOG_IN)
                    : new BinaryWebSocketFrame(Unpooled.buffer().writeByte(Events.LOG_IN).writeByte(1)));
            assertNotNull(write.promise, "Hold the login success write before initializing the session");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void close() {
            write.release();
            channel.finishAndReleaseAll();
            session.close();
            sessions.close();
            game.close();
        }
    }

    /**
     * Captures outbound writes so tests can complete their promises after closure.
     */
    private static final class DelayedWrite extends ChannelOutboundHandlerAdapter {

        /**
         * Creates a delayed write instance.
         */
        private DelayedWrite() {
        }

        /**
         * Promise used by this regression fixture.
         */
        ChannelPromise promise;

        /**
         * Message used by this regression fixture.
         */
        Object message;

        /**
         * {@inheritDoc}
         */
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (this.promise == null) {
                this.promise = promise;
                message = msg;
            } else {
                ReferenceCountUtil.release(msg);
                promise.setSuccess();
            }
        }

        /**
         * Verifies the regression scenario: complete.
         */
        void complete() {
            release();
            promise.setSuccess();
        }

        /**
         * Verifies the regression scenario: release.
         */
        void release() {
            ReferenceCountUtil.release(message);
            message = null;
        }
    }

    /**
     * Records protocol installation during login callback tests.
     */
    private static final class RecordingProtocol implements Protocol {

        /**
         * Creates a recording protocol instance.
         */
        private RecordingProtocol() {
        }

        /**
         * Applications used by this regression fixture.
         */
        final AtomicInteger applications = new AtomicInteger();

        /**
         * {@inheritDoc}
         */
        @Override
        public String getProtocolName() { return "test"; }

        /**
         * {@inheritDoc}
         */
        @Override
        public void applyProtocol(PlayerSession session) { applications.incrementAndGet(); }

        /**
         * {@inheritDoc}
         */
        @Override
        public void applyProtocol(PlayerSession session, boolean clear) { applyProtocol(session); }
    }
}
