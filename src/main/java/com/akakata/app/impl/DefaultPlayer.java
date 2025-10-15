package com.akakata.app.impl;

import com.akakata.app.Player;
import com.akakata.app.PlayerSession;

/**
 * @author Kelvin
 */
public class DefaultPlayer implements Player {

    /**
     * 唯一ID
     */
    protected Object id;

    /**
     * 会话，玩家会话唯一
     */
    protected PlayerSession playerSession;

    /**
     * 空构造函数
     */
    public DefaultPlayer() {

    }

    /**
     * 带ID的构造函数
     *
     * @param id 全局唯一ID，可以是任何形式
     */
    public DefaultPlayer(Object id) {
        super();
        this.id = id;
    }

    @Override
    public boolean isOnline() {
        return playerSession != null && playerSession.isConnected();
    }

    @Override
    public synchronized void logout() {
        playerSession.close();
    }

    @Override
    public Object getId() {
        return id;
    }

    @Override
    public void setId(Object id) {
        this.id = id;
    }

    @Override
    public PlayerSession getPlayerSession() {
        return playerSession;
    }

    @Override
    public void setPlayerSession(PlayerSession playerSession) {
        this.playerSession = playerSession;
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
            if (other.id != null) {
                return false;
            }
        } else if (!id.equals(other.id)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "Player [id=" + id + "]";
    }
}
