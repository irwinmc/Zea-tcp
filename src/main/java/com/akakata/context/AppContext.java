package com.akakata.context;

/**
 * Application constants for bean names and component identifiers.
 * This class only contains constants - no runtime state or methods.
 *
 * @author Kelvin
 */
public final class AppContext {

    private AppContext() {
        // Prevent instantiation
    }

    /**
     * App context
     */
    public static final String APP_CONTEXT = "appContext";
    public static final String APP_SESSION = "game";

    /**
     * Servers
     */
    public static final String SERVER_MANAGER = "serverManager";
    public static final String TCP_SERVER = "tcpServer";
    public static final String HTTP_SERVER = "httpServer";
    public static final String WEB_SOCKET_SERVER = "webSocketServer";

    /**
     * Services with default implementations
     */
    public static final String SESSION_REGISTRY_SERVICE = "sessionManager";

    /**
     * Specific handlers
     */
    public static final String WEB_SOCKET_LOGIN_HANDLER = "webSocketLoginHandler";

    /**
     * Cache store
     */
    public static final String CACHE_STORE = "cacheStore";
}
