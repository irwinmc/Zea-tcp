package com.akakata.event.impl;

import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.event.NetworkEvent;
import io.netty.channel.Channel;

/**
 * Copies an event's source reference and timestamp into an outbound network event.
 *
 * <p>The type is fixed to the network-message opcode. The optional channel is
 * transient and excluded from serialization. Copying does not retain a native payload.
 *
 * @author Kyia
 * @see NetworkEvent
 * @see DefaultEvent
 * @see Events#networkEvent(Object)
 * @see com.akakata.app.Game#sendBroadcast(NetworkEvent)
 */
public class DefaultNetworkEvent extends DefaultEvent implements NetworkEvent {

    /**
     * Serialization version identifier.
     */
    private static final long serialVersionUID = -4779264464285216014L;

    // ==================== 字段 ====================

    /**
     * Associated transport channel.
     */
    private transient Channel channel;

    // ==================== 构造函数 ====================

    /**
     * Creates an outbound network event, copying source and timestamp references when an event is
     * supplied.
     */
    public DefaultNetworkEvent() {
        super.setType(Events.NETWORK_MESSAGE);
    }

    /**
     * Creates an outbound network event, copying source and timestamp references when an event is
     * supplied.
     *
     * @param event the event to process
     */
    public DefaultNetworkEvent(Event event) {
        this(event, null);
    }

    /**
     * Creates an outbound network event, copying source and timestamp references when an event is
     * supplied.
     *
     * @param event the event to process
     * @param channel the Netty transport channel
     */
    public DefaultNetworkEvent(Event event, Channel channel) {
        this.setSource(event.getSource());
        this.setTimeStamp(event.getTimeStamp());
        this.channel = channel;
        super.setType(Events.NETWORK_MESSAGE);
    }

    // ==================== Getter/Setter ====================

    /**
     * Returns the associated Netty channel.
     *
     * @return the associated Netty channel
     */
    public Channel getChannel() {
        return channel;
    }

    /**
     * Sets the associated Netty channel.
     *
     * @param channel the Netty transport channel
     */
    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    // ==================== 重写方法 ====================

    /**
     * Rejects attempts to change the fixed network-message type.
     *
     * @param type the event opcode
     * @throws IllegalArgumentException always, because the event type is fixed
     */
    @Override
    public void setType(int type) {
        throw new IllegalArgumentException(
                "Event type of this class is already set to NETWORK_MESSAGE. It should not be reset.");
    }
}
