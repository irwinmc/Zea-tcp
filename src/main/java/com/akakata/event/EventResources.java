package com.akakata.event;

import com.akakata.communication.MessageBuffer;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;

/**
 * Captures and manages native payload references for asynchronous event deliveries.
 *
 * <p>Payload references are captured independently of mutable events so cleanup
 * always releases the reference that was originally retained.

 * <p>Capture the reference before starting asynchronous work:
 * <pre>{@code
 * Object payload = EventResources.retainPayload(event);
 * try {
 *     // Process the event while its native payload is retained.
 * } finally {
 *     EventResources.releasePayload(payload);
 * }
 * }</pre>
 */
public final class EventResources {
    /**
     * Prevents direct construction of this utility or singleton component.
     */
    private EventResources() {}

    /**
     * Captures and retains the event's native payload reference.
     *
     * <p>Release the returned reference rather than looking up the event source again,
     * because the event may be mutated after capture.
     *
     * @param event the event to process
     * @return the retained payload, or {@code null} if no native reference exists
     */
    public static Object retainPayload(Event event) {
        return ReferenceCountUtil.retain(payloadReference(event));
    }

    /**
     * Releases one captured native reference. Null and non-reference-counted values are ignored.
     *
     * @param payload the encoded or captured payload
     */
    public static void releasePayload(Object payload) {
        ReferenceCountUtil.release(payload);
    }

    /**
     * Captures the native reference-counted payload without retaining it.
     *
     * @param event the event to process
     * @return the reference-counted payload, or {@code null} for nonnative payloads
     */
    public static Object payloadReference(Event event) {
        Object source = event.getSource();
        Object payload = source instanceof MessageBuffer<?> buffer ? buffer.getNativeBuffer() : source;
        return payload instanceof ReferenceCounted ? payload : null;
    }
}
