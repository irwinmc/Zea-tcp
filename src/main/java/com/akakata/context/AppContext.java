package com.akakata.context;

/**
 * Application context providing centralized bean access.
 * Now delegates to ServerContext instead of Spring IoC.
 *
 * @author Kelvin
 */
public class AppContext {

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
    public static final String GAME_ADMIN_SERVICE = "gameAdminService";

    /**
     * Netty default handlers
     */
    public static final String HASHED_WHEEL_TIMER = "hashedWheelTimer";
    public static final String LENGTH_FIELD_PREPENDER = "lengthFieldPrepender";
    public static final String LENGTH_FIELD_BASED_FRAME_DECODER = "lengthFieldBasedFrameDecoder";
    public static final String IDLE_CHECK_HANDLER = "idleCheckHandler";

    /**
     * Specific handlers
     */
    public static final String WEB_SOCKET_LOGIN_HANDLER = "webSocketLoginHandler";

    /**
     * Other Netty specific beans
     */
    public static final String NETTY_CHANNEL_GROUP = "defaultChannelGroup";

    /**
     * Cache store
     */
    public static final String CACHE_STORE = "cacheStore";

    /**
     * The application context (now uses ServerContext instead of Spring).
     */
    private static ServerContext serverContext;

    /**
     * Set the server context instance.
     * Should be called once at application startup.
     *
     * @param context the server context
     */
    public static void setServerContext(ServerContext context) {
        serverContext = context;
    }

    /**
     * Get bean. This method is used to retrieve a bean by its name.
     *
     * @param beanName bean name
     * @return Bean object or null if not found
     */
    public static Object getBean(String beanName) {
        if (beanName == null || serverContext == null) {
            return null;
        }
        return serverContext.getBean(beanName);
    }

    /**
     * Called from the main method once the application is initialized.
     */
    public void initialized() {
        // Compatibility method for legacy code
    }
}
