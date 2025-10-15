package com.akakata.app;

import com.akakata.event.Event;
import com.akakata.protocols.Protocol;

/**
 * @author Kelvin
 */
public interface PlayerSession extends Session {

    /**
     * Each session is associated with a {@link Player}. This is the actual
     * human or machine using this session.
     *
     * @return player
     */
    Player getPlayer();

    /**
     * Method used to set the player, usually after the actually login.
     *
     * @param player player
     */
    void setPlayer(Player player);

    /**
     * Each user session is attached to a game. This method is used to retrieve that game object.
     *
     * @return Game
     */
    Game getGame();

    /**
     * Method used to set the game for a particular session.
     *
     * @param game Game
     */
    void setGame(Game game);

    /**
     * Get the {@link Protocol} associated with this session.
     *
     * @return Protocol of the user session
     */
    Protocol getProtocol();

    /**
     * Set the protocol on the user session.
     *
     * @param protocol the protocol to be used
     */
    void setProtocol(Protocol protocol);

    /**
     * The event to be send to the {@link Game} to which the PlayerSession
     * belongs. Behavior is unspecified if message is sent when a room change is
     * taking place.
     *
     * @param event The event to send to the {@link Game}
     */
    void sendToGame(Event event);
}
