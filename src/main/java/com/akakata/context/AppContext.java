package com.akakata.context;

/**
 * Defines dependency names and pipeline handler names shared by server components.
 *
 * @author Kelvin
 */
public final class AppContext {

    /**
     * Prevents direct construction of this utility or singleton component.
     */
    private AppContext() {
        // Prevent instantiation
    }

    /**
     * App context.
     */
    public static final String APP_CONTEXT = "appContext";
    /**
     * Dependency or pipeline name for app session.
     */
    public static final String APP_SESSION = "game";

    /**
     * Servers.
     */
    public static final String SERVER_MANAGER = "serverManager";
    /**
     * Dependency or pipeline name for tcp server.
     */
    public static final String TCP_SERVER = "tcpServer";
    /**
     * Dependency or pipeline name for http server.
     */
    public static final String HTTP_SERVER = "httpServer";
    /**
     * Dependency or pipeline name for web socket server.
     */
    public static final String WEB_SOCKET_SERVER = "webSocketServer";

    /**
     * Services with default implementations.
     */
    public static final String SESSION_REGISTRY_SERVICE = "sessionManager";

    /**
     * Specific handlers.
     */
    public static final String WEB_SOCKET_LOGIN_HANDLER = "webSocketLoginHandler";

    /**
     * Cache store.
     */
    public static final String CACHE_STORE = "cacheStore";
}
