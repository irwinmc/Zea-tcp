package com.akakata.app.impl;

import com.akakata.app.PlayerSession;
import com.akakata.event.impl.DefaultSessionEventHandler;

/**
 * Provides the default application Game and registers handlers for logged-in players.
 *
 * <p>The default handler queues incoming session messages on the Game's serial
 * executor without automatically sending them to any player. Applications register
 * Game handlers and explicitly choose unicast, selected recipients or broadcasting.
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
     * Creates the default Game with a bounded, application-selected queue capacity.
     *
     * @param queueCapacity the maximum pending Game tasks, at least 16
     */
    public DefaultGame(int queueCapacity) {
        super("ZEA_GAME", queueCapacity);
    }

    /**
     * Registers a default handler that forwards inbound Game work and sends outbound events.
     *
     * @param playerSession the player session
     */
    @Override
    public void onLogin(PlayerSession playerSession) {
        DefaultSessionEventHandler listener = new DefaultSessionEventHandler(playerSession);
        playerSession.addHandler(listener);
    }
}
