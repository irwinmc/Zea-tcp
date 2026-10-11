package com.akakata.communication.impl;

import com.akakata.communication.MessageBuffer;
import com.akakata.communication.Transform;
import com.akakata.util.NettyUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

/**
 * Adapts a Netty byte buffer to the message-buffer interface.
 *
 * <p>Construction with an existing buffer does not retain it. The caller owns the
 * native reference and must release it when finished. String and object writes copy
 * from temporary buffers and release those temporary references after the copy.
 * The wrapper does not synchronize access to reader or writer indices.
 *
 * <p>Release the native reference after local use:
 * <pre>{@code
 * NettyMessageBuffer buffer = new NettyMessageBuffer();
 * try {
 *     buffer.writeInt(42);
 *     int value = buffer.readInt();
 * } finally {
 *     buffer.getNativeBuffer().release();
 * }
 * }</pre>

 * @author Kelvin
 */
public class NettyMessageBuffer implements MessageBuffer<ByteBuf> {

    /**
     * Native byte buffer owned or borrowed according to the wrapper contract.
     */
    private final ByteBuf buffer;

    /**
     * Wraps the supplied buffer without retaining it, or allocates a new caller-owned buffer when
     * no argument is supplied.
     */
    public NettyMessageBuffer() {
        buffer = Unpooled.buffer();
    }

    /**
     * Wraps the supplied buffer without retaining it, or allocates a new caller-owned buffer when
     * no argument is supplied.
     *
     * @param buffer the existing buffer reference, which is not retained
     */
    public NettyMessageBuffer(ByteBuf buffer) {
        this.buffer = buffer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReadable() {
        return buffer.isReadable();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readableBytes() {
        return buffer.readableBytes();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readByte() {
        return buffer.readByte();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] readBytes(int length) {
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        return bytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readBytes(byte[] dst) {
        buffer.readBytes(dst);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readBytes(byte[] dst, int dstIndex, int length) {
        buffer.readBytes(dst, dstIndex, length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readUnsignedByte() {
        return buffer.readUnsignedByte();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public char readChar() {
        return buffer.readChar();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readShort() {
        return buffer.readShort();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readUnsignedShort() {
        return buffer.readUnsignedShort();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readMedium() {
        return buffer.readMedium();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readUnsignedMedium() {
        return buffer.readUnsignedMedium();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readInt() {
        return buffer.readInt();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long readUnsignedInt() {
        return buffer.readUnsignedInt();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long readLong() {
        return buffer.readLong();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public float readFloat() {
        return buffer.readFloat();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double readDouble() {
        return buffer.readDouble();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String readString() {
        return NettyUtils.readString(buffer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String[] readStrings(int numOfStrings) {
        return NettyUtils.readStrings(buffer, numOfStrings);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <V> V readObject(Transform<ByteBuf, V> converter) {
        return NettyUtils.readObject(buffer, converter);
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public MessageBuffer<ByteBuf> writeByte(byte b) {
        buffer.writeByte(b);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageBuffer<ByteBuf> writeBytes(byte[] src) {
        buffer.writeBytes(src);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageBuffer<ByteBuf> writeChar(int value) {
        buffer.writeChar(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageBuffer<ByteBuf> writeShort(int value) {
        buffer.writeShort(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageBuffer<ByteBuf> writeMedium(int value) {
        buffer.writeMedium(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageBuffer<ByteBuf> writeInt(int value) {
        buffer.writeInt(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageBuffer<ByteBuf> writeLong(long value) {
        buffer.writeLong(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageBuffer<ByteBuf> writeFloat(float value) {
        buffer.writeFloat(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageBuffer<ByteBuf> writeDouble(double value) {
        buffer.writeDouble(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageBuffer<ByteBuf> writeString(String message) {
        ByteBuf strBuf = NettyUtils.writeString(message);
        try {
            buffer.writeBytes(strBuf);
        } finally {
            ReferenceCountUtil.release(strBuf);
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageBuffer<ByteBuf> writeStrings(String... messages) {
        ByteBuf strMultiBuf = NettyUtils.writeStrings(messages);
        try {
            buffer.writeBytes(strMultiBuf);
        } finally {
            ReferenceCountUtil.release(strMultiBuf);
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <V> MessageBuffer<ByteBuf> writeObject(Transform<V, ByteBuf> converter, V object) {
        ByteBuf objBuf = NettyUtils.writeObject(converter, object);
        try {
            buffer.writeBytes(objBuf);
        } finally {
            ReferenceCountUtil.release(objBuf);
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ByteBuf getNativeBuffer() {
        return buffer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] array() {
        return buffer.array();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        buffer.clear();
    }
}
