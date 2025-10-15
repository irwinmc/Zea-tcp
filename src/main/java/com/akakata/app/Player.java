package com.akakata.app;

/**
 * @author Kelvin
 */
public interface Player {

    /**
     * 获取玩家ID
     *
     * @return 玩家ID
     */
    Object getId();

    /**
     * 设置玩家ID
     *
     * @param id 玩家ID
     */
    void setId(Object id);

    /**
     * 获取玩家会话
     *
     * @return 玩家会话
     */
    PlayerSession getPlayerSession();

    /**
     * 设置玩家会话
     *
     * @param playerSession 玩家会话
     */
    void setPlayerSession(PlayerSession playerSession);

    /**
     * 是否在线
     *
     * @return 如果在线返回true，离线返回false
     */
    boolean isOnline();

    /**
     * 登出
     */
    void logout();
}
