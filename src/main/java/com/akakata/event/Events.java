package com.akakata.event;

import com.akakata.event.impl.DefaultEvent;
import com.akakata.event.impl.DefaultNetworkEvent;

/**
 * Defines event opcodes and factories for ordinary, inbound and outbound events.
 *
 * <p>Factories set timestamps to epoch milliseconds and keep the supplied source
 * reference without retaining or copying it. {@link #ANY} is a handler subscription
 * selector rather than an application event opcode.
 *
 * @author Kelvin
 * @see Event
 * @see NetworkEvent
 * @see EventHandler
 */
public class Events {

    // ==================== 特殊类型 ====================

    /**
     * Creates an events instance.
     */
    public Events() {
    }

    /**
     * Wildcard handler subscription selector; not an application event opcode.
     */
    public final static byte ANY = 0x00;

    // ==================== 生命周期事件 (0x11-0x19) ====================

    /**
     * Opcode requesting authentication.
     */
    public final static byte LOG_IN = 0x11;

    /**
     * Opcode reporting successful authentication.
     */
    public final static byte LOG_IN_SUCCESS = 0x14;

    /**
     * Opcode reporting failed authentication.
     */
    public final static byte LOG_IN_FAILURE = 0x15;

    /**
     * Opcode requesting logout.
     */
    public final static byte LOG_OUT = 0x17;

    /**
     * Opcode reporting successful logout.
     */
    public final static byte LOG_OUT_SUCCESS = 0x18;

    /**
     * Opcode reporting failed logout.
     */
    public final static byte LOG_OUT_FAILURE = 0x19;

    // ==================== 游戏事件 (0x2a-0x2e) ====================

    /**
     * Opcode requesting entry to a game.
     */
    public final static byte GAME_ENTER = 0x2a;

    /**
     * Opcode reporting successful game entry.
     */
    public final static byte GAME_ENTER_SUCCESS = 0x2c;

    /**
     * Opcode reporting failed game entry.
     */
    public final static byte GAME_ENTER_FAILURE = 0x2d;

    /**
     * Opcode reporting departure from a game.
     */
    public final static byte GAME_LEAVE = 0x2e;

    // ==================== 元数据和控制事件 (0x30-0x40) ====================

    /**
     * Opcode carrying protocol-version information.
     */
    public final static byte PROTOCOL_VERSION = 0x30;

    /**
     * Opcode indicating component startup.
     */
    public final static byte START = 0x31;

    /**
     * Opcode indicating component shutdown.
     */
    public final static byte STOP = 0x32;

    /**
     * Opcode for inbound client data delivered to a session.
     */
    public final static byte SESSION_MESSAGE = 0x33;

    /**
     * Opcode for outbound data sent to clients.
     */
    public final static byte NETWORK_MESSAGE = 0x34;

    /**
     * Opcode indicating connection termination.
     */
    public final static byte DISCONNECT = 0x36;

    /**
     * Opcode indicating an exceptional condition.
     */
    public final static byte EXCEPTION = 0x40;

    // ==================== 工厂方法 ====================

    /**
     * Creates an event with the supplied source, type and current epoch-millisecond timestamp.
     *
     * <p>The source reference is borrowed without retaining or copying it.
     *
     * @param source the payload or source object, possibly null
     * @param eventType the event opcode or subscription selector
     * @return the newly created event
     */
    public static Event event(Object source, int eventType) {
        DefaultEvent event = new DefaultEvent();
        event.setSource(source);
        event.setType(eventType);
        event.setTimeStamp(System.currentTimeMillis());
        return event;
    }

    /**
     * Creates an outbound event with the fixed network-message opcode.
     *
     * <p>The source reference is borrowed without retaining or copying it.
     *
     * @param source the payload or source object, possibly null
     * @return the newly created outbound event
     */
    public static NetworkEvent networkEvent(Object source) {
        Event event = event(source, Events.NETWORK_MESSAGE);
        NetworkEvent networkEvent = new DefaultNetworkEvent(event);
        return networkEvent;
    }

    /**
     * Creates an inbound event with the session-message opcode.
     *
     * <p>The source reference is borrowed without retaining or copying it.
     *
     * @param source the payload or source object, possibly null
     * @return the newly created inbound event
     */
    public static Event dataInEvent(Object source) {
        return event(source, Events.SESSION_MESSAGE);
    }
}
