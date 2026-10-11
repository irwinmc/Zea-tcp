package com.akakata.event;

/**
 * Marks an event as an outbound network message.
 *
 * <p>The default implementation fixes the type to {@link Events#NETWORK_MESSAGE}.
 * Its payload is encoded by the sender's pipeline and may be reused for broadcasts
 * when reference-counted ownership is respected.
 *
 * @author Kelvin
 * @see Event
 * @see com.akakata.event.impl.DefaultNetworkEvent
 * @see Events#networkEvent(Object)
 * @see Events#NETWORK_MESSAGE
 */
public interface NetworkEvent extends Event {

    // 标记接口，无额外方法
    // 所有方法继承自 Event 接口

}
