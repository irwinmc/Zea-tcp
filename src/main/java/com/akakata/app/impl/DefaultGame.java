package com.akakata.app.impl;

import com.akakata.app.PlayerSession;
import com.akakata.event.impl.DefaultSessionEventHandler;

/**
 * @author Kelvin
 */
public class DefaultGame extends AbstractGame {

    public DefaultGame() {
        super("ZEA_GAME");
    }

    @Override
    public void onLogin(PlayerSession playerSession) {
        DefaultSessionEventHandler listener = new DefaultSessionEventHandler(playerSession);
        playerSession.addHandler(listener);
    }
}
