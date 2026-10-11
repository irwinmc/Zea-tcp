package com.akakata.app;

import com.akakata.event.Event;
import com.akakata.protocols.Protocol;

/**
 * Associates a session with a player, a game and a network protocol.
 *
 * @author Kelvin
 */
public interface PlayerSession extends Session {

    /**
     * Returns the player associated with the session.
     *
     * @return the player, or {@code null} when no player has been assigned
     */
    Player getPlayer();

    /**
     * Updates the player associated with the session.
     *
     * @param player the player associated with the session
     */
    void setPlayer(Player player);

    /**
     * Returns the game associated with the session.
     *
     * @return the game, or {@code null} when no game has been assigned
     */
    Game getGame();

    /**
     * Updates the associated game reference without changing either game's membership set.
     *
     * @param game the associated game
     */
    void setGame(Game game);

    /**
     * Returns the associated protocol configuration.
     *
     * @return the protocol, or {@code null} when no protocol has been assigned
     */
    Protocol getProtocol();

    /**
     * Updates the protocol reference without installing or removing pipeline handlers.
     *
     * @param protocol the protocol to be used
     */
    void setProtocol(Protocol protocol);

    /**
     * Submits an event to the associated game dispatcher.
     *
     * @param event The event to send to the {@link Game}
     */
    void sendToGame(Event event);
}
