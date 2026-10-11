package com.akakata.app;

import com.akakata.event.Event;
import com.akakata.event.NetworkEvent;

import java.util.Set;

/**
 * Owns a group of player sessions and routes game events and outbound broadcasts.
 *
 * <p>A session is created before it is connected. Implementations define the login
 * callbacks and are responsible for releasing their sessions and dispatcher when closed.
 *
 * @author Kyia
 */
public interface Game {

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
     * Submits an event to the game dispatcher. A closed default game ignores the event.
     *
     * @param event the event to process
     */
    void send(Event event);

    /**
     * Submits the outbound event to each connected session.
     *
     * <p>Each accepted delivery retains its own native payload reference. The caller
     * retains ownership of the original event payload and may release that reference
     * after this method returns.
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
    void close();
}
