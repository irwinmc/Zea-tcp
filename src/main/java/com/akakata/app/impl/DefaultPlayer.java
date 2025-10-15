package com.akakata.app.impl;

import com.akakata.app.Player;

/**
 * Default implementation of Player - pure data object.
 * No session references to avoid circular dependencies.
 *
 * @author Kelvin
 */
public class DefaultPlayer implements Player {

    /**
     * 唯一ID
     */
    protected String id;

    /**
     * 玩家昵称
     */
    protected String nickname;

    /**
     * 空构造函数
     */
    public DefaultPlayer() {
    }

    /**
     * 带ID的构造函数
     *
     * @param id 玩家ID
     */
    public DefaultPlayer(String id) {
        this.id = id;
    }

    /**
     * 带ID和昵称的构造函数
     *
     * @param id       玩家ID
     * @param nickname 玩家昵称
     */
    public DefaultPlayer(String id, String nickname) {
        this.id = id;
        this.nickname = nickname;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getNickname() {
        return nickname;
    }

    @Override
    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        DefaultPlayer other = (DefaultPlayer) obj;
        if (id == null) {
            return other.id == null;
        }
        return id.equals(other.id);
    }

    @Override
    public String toString() {
        return "Player[id=" + id + ", nickname=" + nickname + "]";
    }
}
