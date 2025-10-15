package com.akakata.context;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * @author Kelvin
 */
public class AppContext implements ApplicationContextAware {

    /**
     * App context
     */
    public static final String APP_CONTEXT = "appContext";
    public static final String APP_SESSION = "appSession";

    /**
     * Servers
     */
    public static final String SERVER_MANAGER = "serverManager";
    public static final String FLASH_POLICY_SERVER = "flashPolicyServer";
    public static final String TCP_SERVER = "tcpServer";
    public static final String HTTP_SERVER = "httpServer";
    public static final String WEB_SOCKET_SERVER = "webSocketServer";

    /**
     * Services with default implementations
     */
    public static final String SESSION_REGISTRY_SERVICE = "sessionRegistryService";
    public static final String GAME_ADMIN_SERVICE = "gameAdminService";
    public static final String TASK_MANAGER_SERVICE = "taskManagerService";

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
     * The spring application context.
     */
    private static ApplicationContext applicationContext;

    /**
     * Get bean. This method is used to retrieve a bean by its name.
     *
     * @param beanName bean name
     * @return Bean object
     */
    public static Object getBean(String beanName) {
        if (beanName == null) {
            return null;
        }
        return applicationContext.getBean(beanName);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        AppContext.applicationContext = applicationContext;
    }

    /**
     * Called from the main method once the application is initialized.
     */
    public void initialized() {

    }
}
