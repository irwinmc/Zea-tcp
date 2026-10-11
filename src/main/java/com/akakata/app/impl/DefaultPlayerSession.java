package com.akakata.app.impl;

import com.akakata.app.Game;
import com.akakata.app.Player;
import com.akakata.app.PlayerSession;
import com.akakata.event.Event;
import com.akakata.event.impl.EventDispatchers;
import com.akakata.protocols.Protocol;

/**
 * Implements a player session using a stable shared-dispatcher shard by default.
 *
 * <p>Closing releases session resources and removes the session from its game, even
 * if sender cleanup fails. Player, game and protocol references are published through
 * volatile fields.
 *
 * @author Kelvin
 */
public class DefaultPlayerSession extends DefaultSession implements PlayerSession {

    /**
     * Player associated with this session or builder.
     */
    protected volatile Player player;

    /**
     * Game associated with this session or component.
     */
    protected volatile Game game;

    /**
     * Protocol associated with the session or installed after authentication.
     */
    protected volatile Protocol protocol;

    /**
     * Builds a player session and selects its stable shared shard when using the default
     * dispatcher.
     *
     * @param playerSessionBuilder the builder supplying player-session state
     */
    protected DefaultPlayerSession(PlayerSessionBuilder playerSessionBuilder) {
        super(playerSessionBuilder);
        this.player = playerSessionBuilder.player;
        this.game = playerSessionBuilder.game;
        this.protocol = playerSessionBuilder.protocol;

        // 如果 eventDispatcher 还是 sharedDispatcher，则替换为专属 shard
        // 这样每个 PlayerSession 直接持有它的 shard 引用，避免每次 fireEvent 都要路由
        if (this.eventDispatcher == EventDispatchers.sharedDispatcher()) {
            this.eventDispatcher = EventDispatchers.getShardForSession(this);
        }
    }

    /**
     * Returns the player associated with the session.
     *
     * @return the player, or {@code null} when no player has been assigned
     */
    @Override
    public Player getPlayer() {
        return player;
    }

    /**
     * Updates the player associated with the session.
     *
     * @param player the player associated with the session
     */
    @Override
    public void setPlayer(Player player) {
        this.player = player;
    }

    /**
     * Returns the game associated with the session.
     *
     * @return the game, or {@code null} when no game has been assigned
     */
    @Override
    public Game getGame() {
        return game;
    }

    /**
     * Updates the associated game reference without changing either game's membership set.
     *
     * @param game the associated game
     */
    @Override
    public void setGame(Game game) {
        this.game = game;
    }

    /**
     * Returns the associated protocol configuration.
     *
     * @return the protocol, or {@code null} when no protocol has been assigned
     */
    @Override
    public Protocol getProtocol() {
        return protocol;
    }

    /**
     * Updates the protocol reference without installing or removing pipeline handlers.
     *
     * @param protocol the protocol to install or associate
     */
    @Override
    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    /**
     * Releases session resources and detaches from the game, including when resource cleanup fails.
     */
    @Override
    public void close() {
        try {
            super.close();
        } finally {
            if (game != null) {
                game.disconnectSession(this);
            }
        }
    }

    /**
     * Submits an event to the associated game dispatcher.
     *
     * @param event the event to process
     */
    @Override
    public void sendToGame(Event event) {
        game.send(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "PlayerSession [id=" + id + ", player=" + player + ", protocol="
                + protocol + ", isShuttingDown=" + isShuttingDown + "]";
    }

    /**
     * Builds player sessions with player, game and protocol associations.
     */
    public static class PlayerSessionBuilder extends SessionBuilder {

        /**
         * Creates a builder with the default initial configuration.
         */
        public PlayerSessionBuilder() {
        }

        /**
         * Player associated with this session or builder.
         */
        protected Player player;

        /**
         * Game associated with this session or component.
         */
        protected Game game;

        /**
         * Protocol associated with the session or installed after authentication.
         */
        protected Protocol protocol;

        /**
         * Constructs a session using this builder's current values after validation.
         *
         * @return the newly constructed session
         */
        @Override
        public PlayerSession build() {
            return new DefaultPlayerSession(this);
        }

        /**
         * Generates an identifier and applies defaults to unset attributes, timestamps and
         * dispatcher selection.
         */
        @Override
        protected void validateAndSetValues() {
            if (eventDispatcher == null) {
                eventDispatcher = EventDispatchers.sharedDispatcher();
            }
            super.validateAndSetValues();
        }

        /**
         * Sets the player associated with the new session.
         *
         * @param player the player associated with the session
         * @return this builder for chaining
         */
        public PlayerSessionBuilder player(Player player) {
            this.player = player;
            return this;
        }

        /**
         * Sets the game associated with the new session.
         *
         * @param game the associated game
         * @return this builder for chaining
         * @throws IllegalArgumentException if the supplied game is null
         */
        public PlayerSessionBuilder game(Game game) {
            if (game == null) {
                throw new IllegalArgumentException("Game instance is null, session will not be constructed");
            }

            this.game = game;
            return this;
        }

        /**
         * Sets the initial protocol association.
         *
         * @param protocol the protocol to install or associate
         * @return this builder for chaining
         */
        public PlayerSessionBuilder protocol(Protocol protocol) {
            this.protocol = protocol;
            return this;
        }
    }
}
