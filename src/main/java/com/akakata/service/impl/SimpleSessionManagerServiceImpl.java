package com.akakata.service.impl;

import com.akakata.app.Session;
import com.akakata.security.Credentials;
import com.akakata.security.SimpleCredentials;
import com.akakata.service.SessionManagerService;
import com.akakata.util.NettyUtils;
import io.netty.buffer.ByteBuf;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple Session Manager
 *
 * @author Kelvin
 */
public class SimpleSessionManagerServiceImpl implements SessionManagerService<Credentials> {

    protected final Map<Credentials, Session> sessions;

    public SimpleSessionManagerServiceImpl() {
        sessions = new ConcurrentHashMap<>(1000);
    }

    @Override
    public Credentials verify(ByteBuf byteBuf) {
        if (byteBuf == null || byteBuf.readableBytes() == 0) {
            return null;
        }
        byteBuf.markReaderIndex();
        String token = NettyUtils.readString(byteBuf);
        byteBuf.resetReaderIndex();
        if (token == null || token.isBlank()) {
            return null;
        }
        SimpleCredentials credentials = new SimpleCredentials();
        credentials.setAttribute("token", token);
        return credentials;
    }

    @Override
    public Session getSession(Credentials key) {
        return sessions.get(key);
    }

    @Override
    public boolean putSession(Credentials key, Session session) {
        if (key == null || session == null) {
            return false;
        }
        if (sessions.put(key, session) == null) {
            return true;
        }
        return false;
    }

    @Override
    public boolean removeSession(Credentials key) {
        return sessions.remove(key) != null;
    }
}
