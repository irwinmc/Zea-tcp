package com.akakata.service;

import com.akakata.app.Session;
import io.netty.buffer.ByteBuf;

/**
 * @author Kelvin
 */
public interface SessionManagerService<T> {

    T verify(ByteBuf byteBuf);

    Session getSession(T key);

    boolean putSession(T key, Session session);

    boolean removeSession(T key);
}
