package com.akakata.event.impl;

import com.akakata.event.EventResources;
import com.akakata.event.NetworkEvent;
import com.akakata.metrics.FlowControlMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Shares lazily encoded application frames across recipients of one ordered Game send.
 *
 * <p>The event is ordinary metadata; its separately reference-counted lease owns
 * the original native payload and cached read-only frames. Each recipient obtains
 * an independently indexed retained duplicate. TCP JSON and WebSocket JSON share
 * the same application frame; SBE uses its own cached frame.
 */
public final class SharedNetworkEvent extends DefaultNetworkEvent {

    /**
     * Serialization version of the metadata wrapper.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Local native ownership and encoded frame cache; excluded from serialization.
     */
    private final transient EncodingLease lease;

    /**
     * Captures immutable fan-out metadata and one original payload reference.
     *
     * @param event the caller-owned network event
     */
    public SharedNetworkEvent(NetworkEvent event) {
        super(event);
        lease = new EncodingLease(event);
    }

    /**
     * Returns the lease captured by dispatchers and socket promises.
     *
     * @return reference-counted ownership of the original payload and cached frames
     */
    public ReferenceCounted payloadLease() { return lease; }

    /**
     * Returns a retained frame duplicate, creating one canonical frame per wire format.
     *
     * @param format the application wire format identifier
     * @param encoder the first recipient's encoder; called once on successful encoding
     * @return a caller-owned read-only duplicate with independent indices
     */
    public ByteBuf encodedFrame(String format, Supplier<ByteBuf> encoder) {
        synchronized (lease) {
            ByteBuf frame = lease.frames.get(format);
            if (frame == null) {
                frame = encoder.get().asReadOnly();
                lease.frames.put(format, frame);
            } else FlowControlMetrics.ENCODING_REUSES.increment();
            return frame.retainedDuplicate();
        }
    }

    /**
     * Releases the initial Game fan-out owner after all recipient admissions finish.
     */
    public void releaseOwner() { lease.release(); }

    /**
     * Retains cached frames and the original payload until every asynchronous owner exits.
     */
    private static final class EncodingLease extends AbstractReferenceCounted {

        /**
         * Original native source independently captured from mutable event metadata.
         */
        private final Object payload;

        /**
         * Canonical read-only application frames, guarded by this lease.
         */
        private final Map<String, ByteBuf> frames = new HashMap<>();

        /**
         * Captures the fan-out payload owner.
         *
         * @param event the original outbound event
         */
        private EncodingLease(NetworkEvent event) { payload = EventResources.retainPayload(event); }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void deallocate() {
            try { frames.values().forEach(ByteBuf::release); }
            finally { frames.clear(); EventResources.releasePayload(payload); }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ReferenceCounted touch(Object hint) { return this; }
    }
}
