package com.akakata.communication;

/**
 * @author Kelvin
 */
public interface MessageBuffer<T> {

    /**
     * @return Returns true if something can be read from this buffer, else false
     */
    boolean isReadable();

    /**
     * Gets the number of readable bytes left in the buffer.
     *
     * @return an integer containing the remaining readable bytes.
     */
    int readableBytes();

    /**
     * Read a single signed byte from the current {@code readerIndex} position
     * of the buffer. It will increment the readerIndex after doing this
     * operation.
     *
     * @return Returns the byte that is read
     */
    int readByte();

    /**
     * Read signed byte of length from the current {@code readerIndex} position
     * of the buffer. It will increment the readerIndex after doing this
     * operation.
     *
     * @param length length to read
     * @return Returns the byte that is read
     */
    byte[] readBytes(int length);

    /**
     * Transfers this buffer's data to the specified destination starting at the
     * current {@code readerIndex} and increases the {@code readerIndex} by the
     * number of the transferred bytes (= {@code dst.length}).
     *
     * @param dst
     */
    void readBytes(byte[] dst);

    /**
     * Transfers this buffer's data to the specified destination starting at the
     * current {@code readerIndex} and increases the {@code readerIndex} by the
     * number of the transferred bytes (= {@code length}).
     *
     * @param dst
     * @param dstIndex
     * @param length
     */
    void readBytes(byte[] dst, int dstIndex, int length);

    /**
     * Gets an unsigned byte at the current {@code readerIndex} and increases
     * the {@code readerIndex} by {@code 1} in this buffer.
     *
     * @return
     */
    int readUnsignedByte();

    /**
     * Gets a 2-byte UTF-16 character at the current {@code readerIndex} and
     * increases the {@code readerIndex} by {@code 2} in this buffer.
     *
     * @return
     */
    char readChar();

    /**
     * Gets a 16-bit short integer at the current {@code readerIndex} and
     * increases the {@code readerIndex} by {@code 2} in this buffer.
     *
     * @return
     */
    int readShort();

    /**
     * Gets an unsigned 16-bit short integer at the current {@code readerIndex}
     * and increases the {@code readerIndex} by {@code 2} in this buffer.
     *
     * @return
     */
    int readUnsignedShort();

    /**
     * Gets a 24-bit medium integer at the current {@code readerIndex} and
     * increases the {@code readerIndex} by {@code 3} in this buffer.
     *
     * @return
     */
    int readMedium();

    /**
     * Gets an unsigned 24-bit medium integer at the current {@code readerIndex}
     * and increases the {@code readerIndex} by {@code 3} in this buffer.
     *
     * @return
     */
    int readUnsignedMedium();

    /**
     * Gets a 32-bit integer at the current {@code readerIndex} and increases
     * the {@code readerIndex} by {@code 4} in this buffer.
     *
     * @return
     */
    int readInt();

    /**
     * Gets an unsigned 32-bit integer at the current {@code readerIndex} and
     * increases the {@code readerIndex} by {@code 4} in this buffer.
     *
     * @return
     */
    long readUnsignedInt();

    /**
     * Gets a 64-bit integer at the current {@code readerIndex} and increases
     * the {@code readerIndex} by {@code 8} in this buffer.
     *
     * @return
     */
    long readLong();

    /**
     * Gets a 32-bit floating point number at the current {@code readerIndex}
     * and increases the {@code readerIndex} by {@code 4} in this buffer.
     *
     * @return
     */
    float readFloat();

    /**
     * Gets a 64-bit floating point number at the current {@code readerIndex}
     * and increases the {@code readerIndex} by {@code 8} in this buffer.
     *
     * @return
     */
    double readDouble();

    String readString();

    String[] readStrings(int numOfStrings);

    /**
     * Reads an object from the underlying buffer and transform the bytes using
     * the supplied transformer to any desired object. This method provide the
     * flexibility to decode the bytes to any type of object.
     *
     * @param converter The converter which will transform the bytes to relevant object.
     * @return The object of type V, or null if the underlying buffer is null or empty.
     */
    <V> V readObject(Transform<T, V> converter);

    MessageBuffer<T> writeByte(byte b);

    /**
     * Transfers the specified source array's data to this buffer starting at
     * the current {@code writerIndex} and increases the {@code writerIndex} by
     * the number of the transferred bytes (= {@code src.length}).
     *
     * @param src
     * @return
     */
    MessageBuffer<T> writeBytes(byte[] src);

    /**
     * Sets the specified 2-byte UTF-16 character at the current
     * {@code writerIndex} and increases the {@code writerIndex} by {@code 2} in
     * this buffer. The 16 high-order bits of the specified value are ignored.
     *
     * @param value
     * @return
     */
    MessageBuffer<T> writeChar(int value);

    /**
     * Sets the specified 16-bit short integer at the current
     * {@code writerIndex} and increases the {@code writerIndex} by {@code 2} in
     * this buffer. The 16 high-order bits of the specified value are ignored.
     *
     * @param value
     * @return
     */
    MessageBuffer<T> writeShort(int value);

    /**
     * Sets the specified 24-bit medium integer at the current
     * {@code writerIndex} and increases the {@code writerIndex} by {@code 3} in
     * this buffer.
     *
     * @param value
     * @return
     */
    MessageBuffer<T> writeMedium(int value);

    /**
     * Sets the specified 32-bit integer at the current {@code writerIndex} and
     * increases the {@code writerIndex} by {@code 4} in this buffer.
     *
     * @param value
     * @return
     */
    MessageBuffer<T> writeInt(int value);

    /**
     * Sets the specified 64-bit long integer at the current {@code writerIndex}
     * and increases the {@code writerIndex} by {@code 8} in this buffer.
     *
     * @param value
     * @return
     */
    MessageBuffer<T> writeLong(long value);

    /**
     * Sets the specified 32-bit floating point number at the current
     * {@code writerIndex} and increases the {@code writerIndex} by {@code 4} in
     * this buffer.
     *
     * @param value
     * @return
     */
    MessageBuffer<T> writeFloat(float value);

    /**
     * Sets the specified 64-bit floating point number at the current
     * {@code writerIndex} and increases the {@code writerIndex} by {@code 8} in
     * this buffer.
     *
     * @param value
     * @return
     */
    MessageBuffer<T> writeDouble(double value);

    MessageBuffer<T> writeString(String message);

    MessageBuffer<T> writeStrings(String... message);

    /**
     * Most implementations will write an object to the underlying buffer after
     * converting the incoming object using the transformer into a byte array.
     * This method provides the flexibility to encode any type of object, to a
     * byte array or buffer(mostly).
     *
     * @param converter
     * @param object
     * @return
     */
    <V> MessageBuffer<T> writeObject(Transform<V, T> converter, V object);

    /**
     * Returns the actual buffer implementation that is wrapped in this
     * IMessageBuffer instance.
     *
     * @return
     */
    T getNativeBuffer();

    /**
     * Returns the backing byte array of this buffer.
     *
     * @return
     */
    byte[] array();

    /**
     * Clears the contents of this buffer.
     */
    void clear();
}
