package com.akakata.util;

/**
 * Defines node identity and reconnect attribute names and defaults.
 *
 * @author Kelvin
 */
public class ZealConfig {

    /**
     * Creates a zeal config instance.
     */
    public ZealConfig() {
    }

    /**
     * System-property key used to prefix generated identifiers with a node name.
     */
    public static final String NODE_NAME = "ZealNode";
    /**
     * Attribute key identifying reconnect credentials.
     */
    public static final String RECONNECT_KEY = "RECONNECT_KEY";
    /**
     * Attribute key identifying the reconnect registry.
     */
    public static final String RECONNECT_REGISTRY = "RECONNECT_REGISTRY";

    /**
     * Default reconnect window in milliseconds.
     */
    public static final int DEFAULT_RECONNECT_DELAY = 5 * 60 * 1000;


}
