package com.akakata.app;

import com.akakata.event.Event;
import com.akakata.event.EventDispatcher;
import com.akakata.event.NetworkEvent;

import java.util.Set;

/**
 * Defines application-wide player membership, shared events and outbound broadcasts.
 *
 * <p>The default server context creates one Game for the application. It owns a
 * serial business executor for shared events and broadcasts; player sessions use
 * separate shared session executors. No room selection is required.
 * A session is created before it is connected. Implementations define the login
 * callbacks and are responsible for releasing their sessions and dispatcher when closed.
 *
 * @author Kyia
 */
public interface Game extends AutoCloseable {

    /**
     * Creates a player session associated with this game without connecting it.
     *
     * @return the newly created player session
     * @throws IllegalStateException if the default game implementation is closed
     */
    PlayerSession createPlayerSession();

    /**
     * Installs application event handlers after successful authentication and protocol setup.
     *
     * @param playerSession the player session
     */
    void onLogin(PlayerSession playerSession);

    /**
     * Connects a session to the game if both are open and it is not already connected.
     *
     * <p>The default implementation takes the session monitor before the game monitor.
     * The connection callback runs after its own synchronized blocks exit; a caller may
     * still hold the session monitor. A failing callback closes the session.
     *
     * @param playerSession the player session
     * @return {@code true} if accepted; {@code false} if closed or already connected
     */
    boolean connectSession(PlayerSession playerSession);

    /**
     * Runs the application callback after a session joins the game. The default implementation does
     * nothing.
     *
     * @param playerSession the player session
     */
    void afterSessionConnect(PlayerSession playerSession);

    /**
     * Removes a session from this game and releases its event registrations.
     *
     * <p>This operation does not itself close the transport. Use the session's
     * {@code close()} method for complete session cleanup.
     *
     * @param playerSession the player session
     * @return {@code true} if the session was present and removed
     */
    boolean disconnectSession(PlayerSession playerSession);

    /**
     * Returns the game's live session membership set.
     *
     * <p>Use lifecycle methods to coordinate membership changes with session state.
     *
     * @return the live set of player sessions
     */
    Set<PlayerSession> getSessions();

    /**
     * Returns the application dispatcher used to register shared business-event handlers.
     *
     * <p>The default Game owns one serial Netty business executor, separate from the
     * session shards. Close the Game to release it rather than closing the dispatcher
     * independently while the Game remains active.
     *
     * @return the application event dispatcher
     * @since 0.8.0
     */
    EventDispatcher getEventDispatcher();

    /**
     * Submits an event to the game dispatcher. A closed default game ignores the event.
     *
     * @param event the event to process
     */
    void send(Event event);

    /**
     * Queues an outbound broadcast before submitting targeted recipient deliveries.
     *
     * <p>Each accepted delivery retains its own native payload reference. The caller
     * retains ownership of the original event payload and may release that reference
     * after this method returns. The default implementation serializes broadcasts
     * with Game events, then queues each connected recipient's delivery on its own
     * session executor. Saturation or shutdown can discard a delivery.
     *
     * @param networkEvent the outbound event to broadcast
     */
    void sendBroadcast(NetworkEvent networkEvent);

    /**
     * Stops accepting sessions, closes connected sessions and releases the game dispatcher.
     *
     * <p>The default implementation continues closing remaining sessions when an
     * individual session fails, and performs cleanup outside the game monitor.
     */
    @Override
    void close();
}
