package com.akakata.app.impl;

import com.akakata.app.PlayerSession;
import com.akakata.event.impl.DefaultSessionEventHandler;

/**
 * Provides the default application Game and registers handlers for logged-in players.
 *
 * <p>The default handler queues incoming session messages on the Game's serial
 * executor for broadcasting to connected players.
 * Applications can subclass the game to install their own handlers.
 *
 * @author Kelvin
 */
public class DefaultGame extends AbstractGame {

    /**
     * Creates the default game named ZEA_GAME.
     */
    public DefaultGame() {
        super("ZEA_GAME");
    }

    /**
     * Registers a default handler that broadcasts inbound messages and sends outbound events.
     *
     * @param playerSession the player session
     */
    @Override
    public void onLogin(PlayerSession playerSession) {
        DefaultSessionEventHandler listener = new DefaultSessionEventHandler(playerSession);
        playerSession.addHandler(listener);
    }
}
