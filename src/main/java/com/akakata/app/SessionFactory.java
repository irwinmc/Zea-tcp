package com.akakata.app;

/**
 * @author Kyia
 */
public interface SessionFactory {

    /**
     * Create new session
     *
     * @return session
     */
    Session newSession();

    /**
     * Create new player session according to the game
     *
     * @param game Game to connected
     * @return PlayerSession
     */
    PlayerSession newPlayerSession(Game game);
}
