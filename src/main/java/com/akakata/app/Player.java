package com.akakata.app;

/**
 * Player data entity - represents player information.
 * This is a pure data object without session references to avoid circular dependencies.
 *
 * @author Kelvin
 */
public interface Player {

    /**
     * 获取玩家ID
     *
     * @return 玩家ID
     */
    String getId();

    /**
     * 设置玩家ID
     *
     * @param id 玩家ID
     */
    void setId(String id);

    /**
     * 获取玩家昵称
     *
     * @return 玩家昵称
     */
    String getNickname();

    /**
     * 设置玩家昵称
     *
     * @param nickname 玩家昵称
     */
    void setNickname(String nickname);
}
