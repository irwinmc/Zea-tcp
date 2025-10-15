package com.akakata.context.module;

import com.akakata.app.Game;
import com.akakata.context.ConfigurationManager;
import com.akakata.handlers.LoginHandler;
import com.akakata.handlers.WebSocketLoginHandler;
import com.akakata.handlers.codec.*;
import com.akakata.protocols.Protocol;
import com.akakata.protocols.impl.JsonProtocol;
import com.akakata.protocols.impl.SbeProtocol;
import com.akakata.protocols.impl.WebSocketProtocol;
import com.akakata.security.Credentials;
import com.akakata.service.SessionManagerService;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.LengthFieldPrepender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProtocolModule extends AbstractModule {

    private static final Logger LOG = LoggerFactory.getLogger(ProtocolModule.class);

    private final ConfigurationManager configurationManager;

    public ProtocolModule(ConfigurationManager configurationManager) {
        this.configurationManager = configurationManager;
    }

    @Provides
    @Singleton
    LengthFieldPrepender provideLengthFieldPrepender() {
        return new LengthFieldPrepender(2, false);
    }

    @Provides
    @Singleton
    SbeEventDecoder provideSbeEventDecoder() {
        return new SbeEventDecoder();
    }

    @Provides
    @Singleton
    SbeEventEncoder provideSbeEventEncoder() {
        return new SbeEventEncoder();
    }

    @Provides
    @Singleton
    JsonDecoder provideJsonDecoder() {
        return new JsonDecoder();
    }

    @Provides
    @Singleton
    JsonEncoder provideJsonEncoder() {
        return new JsonEncoder();
    }

    @Provides
    @Singleton
    WebSocketEventDecoder provideWebSocketEventDecoder() {
        return new WebSocketEventDecoder();
    }

    @Provides
    @Singleton
    WebSocketEventEncoder provideWebSocketEventEncoder() {
        return new WebSocketEventEncoder();
    }

    @Provides
    @Singleton
    EventDecoder provideLegacyEventDecoder() {
        return new EventDecoder();
    }

    @Provides
    @Singleton
    SbeProtocol provideSbeProtocol(SbeEventDecoder decoder,
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
    JsonProtocol provideJsonProtocol(JsonDecoder decoder,
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
    WebSocketProtocol provideWebSocketProtocol(WebSocketEventDecoder decoder,
                                               WebSocketEventEncoder encoder) {
        WebSocketProtocol protocol = new WebSocketProtocol();
        protocol.setWebSocketEventDecoder(decoder);
        protocol.setWebSocketEventEncoder(encoder);
        return protocol;
    }

    @Provides
    @Singleton
    @Named("tcpProtocol")
    Protocol provideTcpProtocol(SbeProtocol sbeProtocol,
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
    ChannelHandler provideTcpDecoder(SbeEventDecoder sbeDecoder,
                                     EventDecoder legacyDecoder) {
        String protocolType = configurationManager.getString("protocol.type", "SBE");
        if ("JSON".equalsIgnoreCase(protocolType)) {
            return legacyDecoder;
        }
        return sbeDecoder;
    }

    @Provides
    @Singleton
    LoginHandler provideLoginHandler(@Named("tcpProtocol") Protocol protocol,
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
    WebSocketLoginHandler provideWebSocketLoginHandler(WebSocketProtocol protocol,
                                                       Game game,
                                                       SessionManagerService<Credentials> sessionManagerService) {
        WebSocketLoginHandler handler = new WebSocketLoginHandler();
        handler.setProtocol(protocol);
        handler.setGame(game);
        handler.setSessionManagerService(sessionManagerService);
        return handler;
    }

}
