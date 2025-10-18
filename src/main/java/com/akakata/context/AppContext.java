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

    /**
     * Specific handlers
     */
    public static final String WEB_SOCKET_LOGIN_HANDLER = "webSocketLoginHandler";

    /**
     * Cache store
     */
    public static final String CACHE_STORE = "cacheStore";

    /**
     * The application context (now uses ServerContext instead of Spring).
     */
    private static ServerContext serverContext;

    /**
     * Set the server context.
     *
     * @param context ServerContext instance
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
