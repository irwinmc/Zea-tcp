package com.akakata.util;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

/**
 * Owns one existing Netty buffer reference and releases it when a try-with-resources scope ends.
 *
 * <p>Construction does not retain another reference. Each holder must be closed
 * exactly once; callers must not release the transferred reference separately.
 *
 * @author Kelvin
 * @since 0.7.8
 */
public class ByteBufHolder implements AutoCloseable {

    /**
     * Native byte buffer owned or borrowed according to the wrapper contract.
     */
    private final ByteBuf buffer;

    /**
     * Wraps an existing buffer reference without retaining it.
     *
     * @param source the payload or source object, possibly null
     * @throws ClassCastException if a nonnull source is not a ByteBuf
     */
    public ByteBufHolder(Object source) {
        this.buffer = (ByteBuf) source;
    }

    /**
     * Returns the held buffer without retaining another reference.
     *
     * @return the buffer released when this holder closes
     */
    public ByteBuf buffer() {
        return buffer;
    }

    /**
     * Releases one reference to the held buffer. This holder is not idempotent.
     */
    @Override
    public void close() {
        ReferenceCountUtil.release(buffer);
    }
}
