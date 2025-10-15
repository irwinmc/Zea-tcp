package com.akakata.app.impl;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.app.SessionFactory;
import com.akakata.app.impl.DefaultPlayerSession.PlayerSessionBuilder;
import com.akakata.app.impl.DefaultSession.SessionBuilder;

/**
 * @author Kyia
 */
public class Sessions implements SessionFactory {

    public static final SessionFactory INSTANCE = new Sessions();

    @Override
    public Session newSession() {
        return new SessionBuilder().build();
    }

    @Override
    public PlayerSession newPlayerSession(Game game) {
        return new PlayerSessionBuilder().game(game).build();
    }
}
