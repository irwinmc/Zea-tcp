package com.akakata.app;

import java.util.Properties;

/**
 * Receives initialization state and configuration when a game starts.
 *
 * @author Kyia
 */
public interface GameStartListener {

    /**
     * Receives configuration when the game starts.
     *
     * @param isInitialized the initialization-state flag supplied by the caller; persistence is not
     * implemented
     * @param properties the game configuration properties
     */
    void start(boolean isInitialized, Properties properties);
}
