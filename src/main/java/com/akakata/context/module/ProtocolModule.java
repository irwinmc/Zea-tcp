package com.akakata.context.module;

import com.akakata.app.Game;
import com.akakata.communication.OutboundLimits;
import com.akakata.context.ConfigurationManager;
import com.akakata.handlers.LoginHandler;
import com.akakata.handlers.WebSocketLoginHandler;
import com.akakata.handlers.codec.EventDecoder;
import com.akakata.handlers.codec.JsonDecoder;
import com.akakata.handlers.codec.JsonEncoder;
import com.akakata.handlers.codec.SbeEventDecoder;
import com.akakata.handlers.codec.SbeEventEncoder;
import com.akakata.handlers.codec.WebSocketEventDecoder;
import com.akakata.handlers.codec.WebSocketEventEncoder;
import com.akakata.protocols.Protocol;
import com.akakata.protocols.impl.JsonProtocol;
import com.akakata.protocols.impl.SbeProtocol;
import com.akakata.protocols.impl.WebSocketProtocol;
import com.akakata.service.LoginService;
import dagger.Module;
import dagger.Provides;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.LengthFieldPrepender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Provides shared codecs, protocol selection and TCP or WebSocket login handlers.
 *
 * @author Kelvin
 */
@Module
public final class ProtocolModule {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ProtocolModule.class);

    /**
     * Prevents direct construction of this utility or singleton component.
     */
    private ProtocolModule() {
    }

    // ============================================================
    // Netty Codecs & Frame Handlers
    // ============================================================

    /**
     * Creates the shared two-byte outbound TCP frame-length prepender.
     *
     * @return the frame-length prepender
     */
    @Provides
    @Singleton
    static LengthFieldPrepender provideLengthFieldPrepender() {
        return new LengthFieldPrepender(2, false);
    }

    /**
     * Provides the sbe event decoder dependency for the application component.
     *
     * @return the sbe event decoder instance
     */
    @Provides
    @Singleton
    static SbeEventDecoder provideSbeEventDecoder() {
        return new SbeEventDecoder();
    }

    /**
     * Provides the sbe event encoder dependency for the application component.
     *
     * @return the sbe event encoder instance
     */
    @Provides
    @Singleton
    static SbeEventEncoder provideSbeEventEncoder() {
        return new SbeEventEncoder();
    }

    /**
     * Provides the json decoder dependency for the application component.
     *
     * @return the json decoder instance
     */
    @Provides
    @Singleton
    static JsonDecoder provideJsonDecoder() {
        return new JsonDecoder();
    }

    /**
     * Provides the json encoder dependency for the application component.
     *
     * @return the json encoder instance
     */
    @Provides
    @Singleton
    static JsonEncoder provideJsonEncoder() {
        return new JsonEncoder();
    }

    /**
     * Provides the web socket event decoder dependency for the application component.
     *
     * @return the web socket event decoder instance
     */
    @Provides
    @Singleton
    static WebSocketEventDecoder provideWebSocketEventDecoder() {
        return new WebSocketEventDecoder();
    }

    /**
     * Provides the web socket event encoder dependency for the application component.
     *
     * @return the web socket event encoder instance
     */
    @Provides
    @Singleton
    static WebSocketEventEncoder provideWebSocketEventEncoder() {
        return new WebSocketEventEncoder();
    }

    /**
     * Provides the legacy event decoder dependency for the application component.
     *
     * @return the legacy event decoder instance
     */
    @Provides
    @Singleton
    static EventDecoder provideLegacyEventDecoder() {
        return new EventDecoder();
    }

    // ============================================================
    // Protocol Implementations
    // ============================================================

    /**
     * Provides the sbe protocol dependency for the application component.
     *
     * @param decoder the inbound decoder or object converter
     * @param encoder the outbound encoder or object converter
     * @param prepender the prepender
     * @return the sbe protocol instance
     */
    @Provides
    @Singleton
    static SbeProtocol provideSbeProtocol(SbeEventDecoder decoder,
                                          SbeEventEncoder encoder,
                                          LengthFieldPrepender prepender) {
        return new SbeProtocol(decoder, encoder, prepender);
    }

    /**
     * Provides the json protocol dependency for the application component.
     *
     * @param decoder the inbound decoder or object converter
     * @param encoder the outbound encoder or object converter
     * @param prepender the prepender
     * @return the json protocol instance
     */
    @Provides
    @Singleton
    static JsonProtocol provideJsonProtocol(JsonDecoder decoder,
                                            JsonEncoder encoder,
                                            LengthFieldPrepender prepender) {
        return new JsonProtocol(decoder, encoder, prepender);
    }

    /**
     * Provides the web socket protocol dependency for the application component.
     *
     * @param decoder the inbound decoder or object converter
     * @param encoder the outbound encoder or object converter
     * @return the web socket protocol instance
     */
    @Provides
    @Singleton
    static WebSocketProtocol provideWebSocketProtocol(WebSocketEventDecoder decoder,
                                                      WebSocketEventEncoder encoder) {
        return new WebSocketProtocol(decoder, encoder);
    }

    // ============================================================
    // Protocol Selectors (Based on Configuration)
    // ============================================================

    /**
     * Selects JSON when protocol.type equals JSON ignoring case; otherwise selects SBE.
     *
     * @param configurationManager the loaded configuration manager
     * @param sbeProtocol the sbe protocol
     * @param jsonProtocol the json protocol
     * @return the configured TCP protocol
     */
    @Provides
    @Singleton
    @Named("tcpProtocol")
    static Protocol provideTcpProtocol(ConfigurationManager configurationManager,
                                       SbeProtocol sbeProtocol,
                                       JsonProtocol jsonProtocol) {
        String protocolType = configurationManager.getString("protocol.type", "SBE");
        if ("JSON".equalsIgnoreCase(protocolType)) {
            LOG.info("TCP protocol configured as JSON");
            return jsonProtocol;
        }
        LOG.info("TCP protocol configured as SBE");
        return sbeProtocol;
    }

    /**
     * Selects the legacy opcode login decoder for JSON mode and the SBE decoder otherwise.
     *
     * @param configurationManager the loaded configuration manager
     * @param sbeDecoder the sbe decoder
     * @param legacyDecoder the legacy decoder
     * @return the selected login decoder
     */
    @Provides
    @Singleton
    @Named("tcpDecoder")
    static ChannelHandler provideTcpDecoder(ConfigurationManager configurationManager,
                                            SbeEventDecoder sbeDecoder,
                                            EventDecoder legacyDecoder) {
        String protocolType = configurationManager.getString("protocol.type", "SBE");
        if ("JSON".equalsIgnoreCase(protocolType)) {
            return legacyDecoder;
        }
        return sbeDecoder;
    }

    // ============================================================
    // Login Handlers
    // ============================================================

    /**
     * Provides the login handler dependency for the application component.
     *
     * @param protocol the protocol to install or associate
     * @param game the associated game
     * @param loginService the authentication coordinator
     * @param outboundLimits the configured per-connection write and encoded-byte budgets
     * @return the login handler instance
     */
    @Provides
    @Singleton
    static LoginHandler provideLoginHandler(@Named("tcpProtocol") Protocol protocol,
                                            Game game,
                                            LoginService loginService, OutboundLimits outboundLimits) {
        return new LoginHandler(protocol, game, loginService, outboundLimits);
    }

    /**
     * Provides the web socket login handler dependency for the application component.
     *
     * @param protocol the protocol to install or associate
     * @param game the associated game
     * @param loginService the authentication coordinator
     * @param outboundLimits the configured per-connection write and encoded-byte budgets
     * @return the web socket login handler instance
     */
    @Provides
    @Singleton
    static WebSocketLoginHandler provideWebSocketLoginHandler(WebSocketProtocol protocol,
                                                              Game game,
                                                              LoginService loginService, OutboundLimits outboundLimits) {
        return new WebSocketLoginHandler(protocol, game, loginService, outboundLimits);
    }
}
