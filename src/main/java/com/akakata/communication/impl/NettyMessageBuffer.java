package com.akakata.communication.impl;

import com.akakata.communication.MessageBuffer;
import com.akakata.communication.Transform;
import com.akakata.util.NettyUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * @author Kelvin
 */
public class NettyMessageBuffer implements MessageBuffer<ByteBuf> {

    private final ByteBuf buffer;

    public NettyMessageBuffer() {
        buffer = Unpooled.buffer();
    }

    public NettyMessageBuffer(ByteBuf buffer) {
        this.buffer = buffer;
    }

    @Override
    public boolean isReadable() {
        return buffer.isReadable();
    }

    @Override
    public int readableBytes() {
        return buffer.readableBytes();
    }

    @Override
    public int readByte() {
        return buffer.readByte();
    }

    @Override
    public byte[] readBytes(int length) {
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        return bytes;
    }

    @Override
    public void readBytes(byte[] dst) {
        buffer.readBytes(dst);
    }

    @Override
    public void readBytes(byte[] dst, int dstIndex, int length) {
        buffer.readBytes(dst, dstIndex, length);
    }

    @Override
    public int readUnsignedByte() {
        return buffer.readUnsignedByte();
    }

    @Override
    public char readChar() {
        return buffer.readChar();
    }

    @Override
    public int readShort() {
        return buffer.readShort();
    }

    @Override
    public int readUnsignedShort() {
        return buffer.readUnsignedShort();
    }

    @Override
    public int readMedium() {
        return buffer.readMedium();
    }

    @Override
    public int readUnsignedMedium() {
        return buffer.readUnsignedMedium();
    }

    @Override
    public int readInt() {
        return buffer.readInt();
    }

    @Override
    public long readUnsignedInt() {
        return buffer.readUnsignedInt();
    }

    @Override
    public long readLong() {
        return buffer.readLong();
    }

    @Override
    public float readFloat() {
        return buffer.readFloat();
    }

    @Override
    public double readDouble() {
        return buffer.readDouble();
    }

    @Override
    public String readString() {
        return NettyUtils.readString(buffer);
    }

    @Override
    public String[] readStrings(int numOfStrings) {
        return NettyUtils.readStrings(buffer, numOfStrings);
    }

    @Override
    public <V> V readObject(Transform<ByteBuf, V> converter) {
        return NettyUtils.readObject(buffer, converter);
    }


    @Override
    public MessageBuffer<ByteBuf> writeByte(byte b) {
        buffer.writeByte(b);
        return this;
    }

    @Override
    public MessageBuffer<ByteBuf> writeBytes(byte[] src) {
        buffer.writeBytes(src);
        return this;
    }

    @Override
    public MessageBuffer<ByteBuf> writeChar(int value) {
        buffer.writeChar(value);
        return this;
    }

    @Override
    public MessageBuffer<ByteBuf> writeShort(int value) {
        buffer.writeShort(value);
        return this;
    }

    @Override
    public MessageBuffer<ByteBuf> writeMedium(int value) {
        buffer.writeMedium(value);
        return this;
    }

    @Override
    public MessageBuffer<ByteBuf> writeInt(int value) {
        buffer.writeInt(value);
        return this;
    }

    @Override
    public MessageBuffer<ByteBuf> writeLong(long value) {
        buffer.writeLong(value);
        return this;
    }

    @Override
    public MessageBuffer<ByteBuf> writeFloat(float value) {
        buffer.writeFloat(value);
        return this;
    }

    @Override
    public MessageBuffer<ByteBuf> writeDouble(double value) {
        buffer.writeDouble(value);
        return this;
    }

    @Override
    public MessageBuffer<ByteBuf> writeString(String message) {
        ByteBuf strBuf = NettyUtils.writeString(message);
        buffer.writeBytes(strBuf);
        return this;
    }

    @Override
    public MessageBuffer<ByteBuf> writeStrings(String... messages) {
        ByteBuf strMultiBuf = NettyUtils.writeStrings(messages);
        buffer.writeBytes(strMultiBuf);
        return this;
    }

    @Override
    public <V> MessageBuffer<ByteBuf> writeObject(Transform<V, ByteBuf> converter, V object) {
        ByteBuf objBuf = NettyUtils.writeObject(converter, object);
        buffer.writeBytes(objBuf);
        return this;
    }

    @Override
    public ByteBuf getNativeBuffer() {
        return buffer;
    }

    @Override
    public byte[] array() {
        return buffer.array();
    }

    @Override
    public void clear() {
        buffer.clear();
    }
}
