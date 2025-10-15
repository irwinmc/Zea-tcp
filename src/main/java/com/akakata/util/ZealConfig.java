package com.akakata.util;

/**
 * @author Kelvin
 */
public class ZealConfig {

    public static final String NODE_NAME = "ZealNode";
    public static final String RECONNECT_KEY = "RECONNECT_KEY";
    public static final String RECONNECT_REGISTRY = "RECONNECT_REGISTRY";

    /**
     * By default, wait for 5 minutes for remote client to reconnect, before
     * closing session.
     */
    public static final int DEFAULT_RECONNECT_DELAY = 5 * 60 * 1000;


}
