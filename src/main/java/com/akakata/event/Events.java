package com.akakata.event;

import com.akakata.event.impl.DefaultEvent;
import com.akakata.event.impl.DefaultNetworkEvent;

/**
 * @author Kelvin
 */
public class Events {

    /**
     * Events should never have this type. But event handlers can choose to have
     * this type to signify that they will handle any type of incoming event.
     * Any type is no type...
     */
    public final static byte ANY = 0x00;

    /**
     * Lifecycle events.
     */
    public final static byte CONNECT = 0x01;
    public final static byte CONNECT_FAILURE = 0x02;

    public final static byte LOG_IN = 0x11;
    public final static byte LOG_IN_SUCCESS = 0x14;
    public final static byte LOG_IN_FAILURE = 0x15;
    public final static byte LOG_OUT = 0x17;
    public final static byte LOG_OUT_SUCCESS = 0x18;
    public final static byte LOG_OUT_FAILURE = 0x19;

    /**
     * Metadata events
     */
    public final static byte GAME_ENTER = 0x2a;
    public final static byte GAME_ENTER_SUCCESS = 0x2c;
    public final static byte GAME_ENTER_FAILURE = 0x2d;
    public final static byte GAME_LEAVE = 0x2e;

    public final static byte PROTOCOL_VERSION = 0x30;
    public final static byte START = 0x31;
    public final static byte STOP = 0x32;
    public final static byte SESSION_MESSAGE = 0x33;
    public final static byte NETWORK_MESSAGE = 0x34;
    public final static byte DISCONNECT = 0x36;

    public final static byte EXCEPTION = 0x40;

    public static Event event(Object source, int eventType) {
        DefaultEvent event = new DefaultEvent();
        event.setSource(source);
        event.setType(eventType);
        event.setTimeStamp(System.currentTimeMillis());
        return event;
    }

    public static NetworkEvent networkEvent(Object source) {
        Event event = event(source, Events.NETWORK_MESSAGE);
        NetworkEvent networkEvent = new DefaultNetworkEvent(event);
        return networkEvent;
    }

    public static Event dataInEvent(Object source) {
        return event(source, Events.SESSION_MESSAGE);
    }
}
