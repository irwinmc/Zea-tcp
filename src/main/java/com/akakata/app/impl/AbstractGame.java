package com.akakata.app.impl;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.app.SessionFactory;
import com.akakata.event.Event;
import com.akakata.event.EventHandler;
import com.akakata.event.NetworkEvent;
import com.akakata.event.impl.EventDispatchers;
import com.akakata.event.impl.NetworkEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Kelvin
 */
public abstract class AbstractGame extends DefaultSession implements Game {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractGame.class);

    /**
     * 玩家会话集合
     */
    protected Set<PlayerSession> sessions;

    protected SessionFactory sessionFactory;

    protected AbstractGame(GameSessionBuilder gameSessionBuilder) {
        super(gameSessionBuilder);

        this.sessions = gameSessionBuilder.sessions;
        this.sessionFactory = gameSessionBuilder.sessionFactory;

        if (gameSessionBuilder.eventDispatcher == null) {
            this.eventDispatcher = EventDispatchers.newAgronaEventDispatcher();
        }
    }

    @Override
    public PlayerSession createPlayerSession() {
        PlayerSession playerSession = sessionFactory.newPlayerSession(this);
        return playerSession;
    }

    /**
     * 登录后调用方法
     *
     * @param playerSession 玩家会话
     */
    @Override
    public abstract void onLogin(PlayerSession playerSession);

    @Override
    public synchronized boolean connectSession(PlayerSession playerSession) {
        if (!isShuttingDown) {
            // Start connect
            playerSession.setStatus(Session.Status.CONNECTING);
            sessions.add(playerSession);
            playerSession.setGame(this);

            // Create and add EventHandlers
            createAndAddEventHandlers(playerSession);

            // Set status to connected
            playerSession.setStatus(Session.Status.CONNECTED);
            afterSessionConnect(playerSession);

            return true;
            // TODO send event to all other sessions.
        } else {
            LOG.warn("Game is shutting down, playerSession {} will not be connected!", playerSession);
            return false;
        }
    }

    @Override
    public void afterSessionConnect(PlayerSession playerSession) {

    }

    @Override
    public synchronized boolean disconnectSession(PlayerSession playerSession) {
        final boolean removeHandlers = eventDispatcher.removeHandlersForSession(playerSession);
        playerSession.getEventDispatcher().clear(); // remove network handlers of the session.
        return removeHandlers && sessions.remove(playerSession);
    }

    @Override
    public void send(Event event) {
        onEvent(event);
    }

    @Override
    public void sendBroadcast(NetworkEvent networkEvent) {
        onEvent(networkEvent);
    }

    @Override
    public synchronized void close() {
        isShuttingDown = true;
        for (PlayerSession session : sessions) {
            session.close();
        }
    }

    @Override
    public Set<PlayerSession> getSessions() {
        return sessions;
    }

    @Override
    public SessionFactory getFactory() {
        return sessionFactory;
    }

    @Override
    public void setFactory(SessionFactory factory) {
        this.sessionFactory = factory;
    }

    /**
     * Method which will create and add event handlers of the player session to
     * the Game Room's EventDispatcher.
     *
     * @param playerSession The session for which the event handlers are created.
     */
    protected void createAndAddEventHandlers(PlayerSession playerSession) {
        // Create a network event listener for the player session.
        EventHandler networkEventHandler = new NetworkEventListener(playerSession);
        // Add the handler to the game room's EventDispatcher so that it will
        // pass game room network events to player session session.
        eventDispatcher.addHandler(networkEventHandler);
        LOG.trace("Added Network handler to EventDispatcher of Game {}, for session: {}", this, playerSession);
    }

    public static class GameSessionBuilder extends SessionBuilder {

        protected Set<PlayerSession> sessions;
        protected String gameName;
        protected SessionFactory sessionFactory;

        @Override
        protected void validateAndSetValues() {
            if (id == null) {
                id = String.valueOf(ID_GENERATOR_SERVICE.generateFor(AbstractGame.class));
            }
            if (sessionAttributes == null) {
                sessionAttributes = new HashMap<>(4);
            }
            if (sessions == null) {
                sessions = new HashSet<>();
            }
            if (sessionFactory == null) {
                sessionFactory = Sessions.INSTANCE;
            }
            creationTime = System.currentTimeMillis();
        }

        public GameSessionBuilder sessions(Set<PlayerSession> sessions) {
            this.sessions = sessions;
            return this;
        }

        public GameSessionBuilder gameName(String gameName) {
            this.gameName = gameName;
            return this;
        }

        public GameSessionBuilder sessionFactory(SessionFactory sessionFactory) {
            this.sessionFactory = sessionFactory;
            return this;
        }
    }
}
