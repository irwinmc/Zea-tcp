package com.akakata.app.impl;

import com.akakata.app.Game;
import com.akakata.app.Player;
import com.akakata.app.PlayerSession;
import com.akakata.concurrent.LaneStrategy.LaneStrategies;
import com.akakata.event.Event;
import com.akakata.event.impl.EventDispatchers;
import com.akakata.protocols.Protocol;

/**
 * @author Kelvin
 */
public class DefaultPlayerSession extends DefaultSession implements PlayerSession {

    /**
     * 所属玩家
     */
    protected Player player;

    /**
     * 所属游戏
     */
    protected Game game;

    /**
     * 使用的通讯协议
     */
    protected Protocol protocol;

    protected DefaultPlayerSession(PlayerSessionBuilder playerSessionBuilder) {
        super(playerSessionBuilder);
        this.player = playerSessionBuilder.player;
        this.game = playerSessionBuilder.game;
        this.protocol = playerSessionBuilder.protocol;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public void setPlayer(Player player) {
        this.player = player;
    }

    @Override
    public Game getGame() {
        return game;
    }

    @Override
    public void setGame(Game game) {
        this.game = game;
    }

    @Override
    public Protocol getProtocol() {
        return protocol;
    }

    @Override
    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    @Override
    public synchronized void close() {
        if (!isShuttingDown) {
            super.close();
            game.disconnectSession(this);
        }
    }

    @Override
    public void sendToGame(Event event) {
        game.send(event);
    }

    @Override
    public String toString() {
        return "PlayerSession [id=" + id + ", player=" + player + ", protocol="
                + protocol + ", isShuttingDown=" + isShuttingDown + "]";
    }

    public static class PlayerSessionBuilder extends SessionBuilder {

        protected Player player;
        protected Game game;
        protected Protocol protocol;

        @Override
        public PlayerSession build() {
            return new DefaultPlayerSession(this);
        }

        @Override
        protected void validateAndSetValues() {
            if (eventDispatcher == null) {
                eventDispatcher = EventDispatchers.newJetlangEventDispatcher(game, LaneStrategies.GROUP_BY_GAME);
            }
            super.validateAndSetValues();
        }

        public PlayerSessionBuilder player(Player player) {
            this.player = player;
            return this;
        }

        public PlayerSessionBuilder game(Game game) {
            if (game == null) {
                throw new IllegalArgumentException("Game instance is null, session will not be constructed");
            }

            this.game = game;
            return this;
        }

        public PlayerSessionBuilder protocol(Protocol protocol) {
            this.protocol = protocol;
            return this;
        }
    }
}
