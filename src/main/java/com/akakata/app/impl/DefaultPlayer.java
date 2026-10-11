package com.akakata.app.impl;

import com.akakata.app.Player;

/**
 * Stores a mutable player identifier and nickname. Equality and hashing use the identifier.
 *
 * @author Kelvin
 */
public class DefaultPlayer implements Player {

    /**
     * Identifier used for player identity.
     */
    protected String id;

    /**
     * Player display name.
     */
    protected String nickname;

    /**
     * Creates a default player instance.
     */
    public DefaultPlayer() {
    }

    /**
     * Creates a default player instance with the supplied dependencies.
     *
     * @param id the identifier
     */
    public DefaultPlayer(String id) {
        this.id = id;
    }

    /**
     * Creates a default player instance with the supplied dependencies.
     *
     * @param id the identifier
     * @param nickname the player display name
     */
    public DefaultPlayer(String id, String nickname) {
        this.id = id;
        this.nickname = nickname;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getId() {
        return id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setId(String id) {
        this.id = id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getNickname() {
        return nickname;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        return result;
    }

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "Player[id=" + id + ", nickname=" + nickname + "]";
    }
}
