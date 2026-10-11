package com.akakata.communication;

import com.akakata.app.PlayerSession;
import com.akakata.communication.impl.ReplyMessage;
import com.akakata.communication.impl.RequestMessage;
import com.akakata.event.Events;

import java.io.Serializable;

/**
 * Creates reply messages and submits error replies through player sessions.
 *
 * @author Kelvin
 */
public class Replys {

    /**
     * Creates a replys instance.
     */
    public Replys() {
    }

    /**
     * Success response code used by reply factories.
     */
    public final static int SUCCESS_CODE = 200;

    /**
     * Creates a reply with the supplied request identifier, response code and data.
     *
     * @param id the identifier
     * @param code the response code
     * @param data the response data, possibly null
     * @return the newly created reply
     */
    public static ReplyMessage reply(int id, int code, Serializable data) {
        ReplyMessage replyMessage = new ReplyMessage();
        replyMessage.setId(id);
        replyMessage.setCode(code);
        replyMessage.setData(data);
        return replyMessage;
    }

    /**
     * Creates a success reply with optional response data.
     *
     * @param id the identifier
     * @param data the response data, possibly null
     * @return the newly created reply
     */
    public static ReplyMessage successReply(int id, Serializable data) {
        return reply(id, SUCCESS_CODE, data);
    }

    /**
     * Creates a success reply with optional response data.
     *
     * @param id the identifier
     * @return the newly created reply
     */
    public static ReplyMessage successReply(int id) {
        return reply(id, SUCCESS_CODE, null);
    }

    /**
     * Creates an error reply without response data.
     *
     * @param id the identifier
     * @param code the response code
     * @return the newly created reply
     */
    public static ReplyMessage errorReply(int id, int code) {
        return reply(id, code, null);
    }

    /**
     * Queues an error reply for the request through the session's outbound event path.
     * A rejected reply closes the session so later updates cannot follow a missing response.
     *
     * @param session the session associated with the operation
     * @param request the inbound request
     * @param code the response code
     */
    public static void error(PlayerSession session, RequestMessage request, int code) {
        if (!session.onEvent(Events.networkEvent(errorReply(request.getId(), code)))) {
            session.close();
        }
    }
}
