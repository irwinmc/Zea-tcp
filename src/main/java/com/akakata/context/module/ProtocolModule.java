package com.akakata.context.module;

import com.akakata.app.Game;
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
import com.akakata.security.Credentials;
import com.akakata.service.SessionManagerService;
import dagger.Module;
import dagger.Provides;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.LengthFieldPrepender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.inject.Singleton;

@Module
public final class ProtocolModule {

    private static final Logger LOG = LoggerFactory.getLogger(ProtocolModule.class);

    private ProtocolModule() {
    }

    @Provides
    @Singleton
    static LengthFieldPrepender provideLengthFieldPrepender() {
        return new LengthFieldPrepender(2, false);
    }

    @Provides
    @Singleton
    static SbeEventDecoder provideSbeEventDecoder() {
        return new SbeEventDecoder();
    }

    @Provides
    @Singleton
    static SbeEventEncoder provideSbeEventEncoder() {
        return new SbeEventEncoder();
    }

    @Provides
    @Singleton
    static JsonDecoder provideJsonDecoder() {
        return new JsonDecoder();
    }

    @Provides
    @Singleton
    static JsonEncoder provideJsonEncoder() {
        return new JsonEncoder();
    }

    @Provides
    @Singleton
    static WebSocketEventDecoder provideWebSocketEventDecoder() {
        return new WebSocketEventDecoder();
    }

    @Provides
    @Singleton
    static WebSocketEventEncoder provideWebSocketEventEncoder() {
        return new WebSocketEventEncoder();
    }

    @Provides
    @Singleton
    static EventDecoder provideLegacyEventDecoder() {
        return new EventDecoder();
    }

    @Provides
    @Singleton
    static SbeProtocol provideSbeProtocol(SbeEventDecoder decoder,
                                          SbeEventEncoder encoder,
                                          LengthFieldPrepender prepender) {
        SbeProtocol protocol = new SbeProtocol();
        protocol.setSbeEventDecoder(decoder);
        protocol.setSbeEventEncoder(encoder);
        protocol.setLengthFieldPrepender(prepender);
        return protocol;
    }

    @Provides
    @Singleton
    static JsonProtocol provideJsonProtocol(JsonDecoder decoder,
                                            JsonEncoder encoder,
                                            LengthFieldPrepender prepender) {
        JsonProtocol protocol = new JsonProtocol();
        protocol.setJsonDecoder(decoder);
        protocol.setJsonEncoder(encoder);
        protocol.setLengthFieldPrepender(prepender);
        return protocol;
    }

    @Provides
    @Singleton
    static WebSocketProtocol provideWebSocketProtocol(WebSocketEventDecoder decoder,
                                                      WebSocketEventEncoder encoder) {
        WebSocketProtocol protocol = new WebSocketProtocol();
        protocol.setWebSocketEventDecoder(decoder);
        protocol.setWebSocketEventEncoder(encoder);
        return protocol;
    }

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

    @Provides
    @Singleton
    static LoginHandler provideLoginHandler(@Named("tcpProtocol") Protocol protocol,
                                            Game game,
                                            SessionManagerService<Credentials> sessionManagerService) {
        LoginHandler handler = new LoginHandler();
        handler.setProtocol(protocol);
        handler.setGame(game);
        handler.setSessionManagerService(sessionManagerService);
        return handler;
    }

    @Provides
    @Singleton
    static WebSocketLoginHandler provideWebSocketLoginHandler(WebSocketProtocol protocol,
                                                              Game game,
                                                              SessionManagerService<Credentials> sessionManagerService) {
        WebSocketLoginHandler handler = new WebSocketLoginHandler();
        handler.setProtocol(protocol);
        handler.setGame(game);
        handler.setSessionManagerService(sessionManagerService);
        return handler;
    }
}
