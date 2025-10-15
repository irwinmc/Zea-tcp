package com.akakata.communication;

import com.akakata.app.PlayerSession;
import com.akakata.communication.impl.ReplyMessage;
import com.akakata.communication.impl.RequestMessage;
import com.akakata.event.Events;

import java.io.Serializable;

/**
 * @author Kelvin
 */
public class Replys {

    public final static int SUCCESS_CODE = 200;

    public static ReplyMessage reply(int id, int code, Serializable data) {
        ReplyMessage replyMessage = new ReplyMessage();
        replyMessage.setId(id);
        replyMessage.setCode(code);
        replyMessage.setData(data);
        return replyMessage;
    }

    public static ReplyMessage successReply(int id, Serializable data) {
        return reply(id, SUCCESS_CODE, data);
    }

    public static ReplyMessage successReply(int id) {
        return reply(id, SUCCESS_CODE, null);
    }

    public static ReplyMessage errorReply(int id, int code) {
        return reply(id, code, null);
    }

    public static void error(PlayerSession session, RequestMessage request, int code) {
        session.onEvent(Events.networkEvent(errorReply(request.getId(), code)));
    }
}
