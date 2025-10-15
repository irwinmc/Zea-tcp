package com.akakata.app;

import java.util.Properties;

/**
 * 游戏启动监听器
 *
 * @author Kyia
 */
public interface GameStartListener {

    /**
     * 用于游戏启动时传入配置
     *
     * @param isInitialized 持久化标识
     *                                           TODO 这个版本不支持持久化
     * @param properties    环境参数配置文件
     */
    void start(boolean isInitialized, Properties properties);
}
