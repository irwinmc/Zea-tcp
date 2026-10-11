package com.akakata.communication;

/**
 * Reads and writes binary message data through a native buffer.
 *
 * <p>Reads advance the reader position and writes advance the writer position.
 * Multi-byte values follow the native implementation's byte order. String and object
 * framing are implementation-specific; the Netty implementation uses the utilities
 * in {@link com.akakata.util.NettyUtils}. Accessing the native buffer does not retain
 * it or transfer ownership.
 *
 * @param <T> the native buffer type
 * @author Kelvin
 */
public interface MessageBuffer<T> {

    /**
     * Tests whether any bytes remain between the reader and writer positions.
     *
     * @return {@code true} if at least one byte can be read
     */
    boolean isReadable();

    /**
     * Returns the number of bytes available from the current reader position.
     *
     * @return the available byte count
     */
    int readableBytes();

    /**
     * Reads a signed 8-bit integer and advances the reader position.
     *
     * @return the signed byte value
     * @throws IndexOutOfBoundsException if insufficient bytes remain in the native buffer
     */
    int readByte();

    /**
     * Copies bytes from the current reader position and advances it by the copied count.
     *
     * @param length length to read
     * @return a new array containing the requested bytes
     * @throws IndexOutOfBoundsException if the requested source or destination range is invalid
     */
    byte[] readBytes(int length);

    /**
     * Copies bytes from the current reader position and advances it by the copied count.
     *
     * @param dst the destination byte array
     * @throws IndexOutOfBoundsException if the requested source or destination range is invalid
     */
    void readBytes(byte[] dst);

    /**
     * Copies bytes from the current reader position and advances it by the copied count.
     *
     * @param dst the destination byte array
     * @param dstIndex the starting index in the destination array
     * @param length the byte count to copy
     * @throws IndexOutOfBoundsException if the requested source or destination range is invalid
     */
    void readBytes(byte[] dst, int dstIndex, int length);

    /**
     * Reads an unsigned 8-bit integer and advances the reader position.
     *
     * @return a value from 0 to 255
     * @throws IndexOutOfBoundsException if insufficient bytes remain in the native buffer
     */
    int readUnsignedByte();

    /**
     * Reads a 16-bit character and advances the reader position.
     *
     * @return the character value
     * @throws IndexOutOfBoundsException if insufficient bytes remain in the native buffer
     */
    char readChar();

    /**
     * Reads a signed 16-bit integer and advances the reader position.
     *
     * @return the signed short value
     * @throws IndexOutOfBoundsException if insufficient bytes remain in the native buffer
     */
    int readShort();

    /**
     * Reads an unsigned 16-bit integer and advances the reader position.
     *
     * @return a value from 0 to 65,535
     * @throws IndexOutOfBoundsException if insufficient bytes remain in the native buffer
     */
    int readUnsignedShort();

    /**
     * Reads a signed 24-bit integer and advances the reader position.
     *
     * @return the signed 24-bit value
     * @throws IndexOutOfBoundsException if insufficient bytes remain in the native buffer
     */
    int readMedium();

    /**
     * Reads an unsigned 24-bit integer and advances the reader position.
     *
     * @return a value from 0 to 16,777,215
     * @throws IndexOutOfBoundsException if insufficient bytes remain in the native buffer
     */
    int readUnsignedMedium();

    /**
     * Reads a signed 32-bit integer and advances the reader position.
     *
     * @return the signed integer value
     * @throws IndexOutOfBoundsException if insufficient bytes remain in the native buffer
     */
    int readInt();

    /**
     * Reads an unsigned 32-bit integer and advances the reader position.
     *
     * @return a value from 0 to 4,294,967,295
     * @throws IndexOutOfBoundsException if insufficient bytes remain in the native buffer
     */
    long readUnsignedInt();

    /**
     * Reads a signed 64-bit integer and advances the reader position.
     *
     * @return the signed long value
     * @throws IndexOutOfBoundsException if insufficient bytes remain in the native buffer
     */
    long readLong();

    /**
     * Reads a 32-bit floating-point value and advances the reader position.
     *
     * @return the decoded float value
     * @throws IndexOutOfBoundsException if insufficient bytes remain in the native buffer
     */
    float readFloat();

    /**
     * Reads a 64-bit floating-point value and advances the reader position.
     *
     * @return the decoded double value
     * @throws IndexOutOfBoundsException if insufficient bytes remain in the native buffer
     */
    double readDouble();

    /**
     * Reads one implementation-framed string and advances the reader position.
     *
     * @return the decoded string, or {@code null} when the implementation cannot decode it
     */
    String readString();

    /**
     * Reads up to the requested number of framed strings.
     *
     * @param numOfStrings the requested number of strings
     * @return an array of decoded strings; undecoded slots may remain null
     */
    String[] readStrings(int numOfStrings);

    /**
     * Reads a framed object through the supplied converter.
     *
     * @param <V> the converted value type
     * @param converter The converter which will transform the bytes to relevant object.
     * @return the converted value, or {@code null} when decoding fails
     */
    <V> V readObject(Transform<T, V> converter);

    /**
     * Writes a signed 8-bit value and advances the writer position.
     *
     * @param b the byte value
     * @return this buffer for chaining
     */
    MessageBuffer<T> writeByte(byte b);

    /**
     * Writes the supplied byte array and advances the writer position.
     *
     * @param src the source byte array
     * @return this buffer for chaining
     */
    MessageBuffer<T> writeBytes(byte[] src);

    /**
     * Writes a 16-bit character and advances the writer position.
     *
     * @param value the value to store or encode
     * @return this buffer for chaining
     */
    MessageBuffer<T> writeChar(int value);

    /**
     * Writes a 16-bit integer and advances the writer position.
     *
     * @param value the value to store or encode
     * @return this buffer for chaining
     */
    MessageBuffer<T> writeShort(int value);

    /**
     * Writes a 24-bit integer and advances the writer position.
     *
     * @param value the value to store or encode
     * @return this buffer for chaining
     */
    MessageBuffer<T> writeMedium(int value);

    /**
     * Writes a 32-bit integer and advances the writer position.
     *
     * @param value the value to store or encode
     * @return this buffer for chaining
     */
    MessageBuffer<T> writeInt(int value);

    /**
     * Writes a 64-bit integer and advances the writer position.
     *
     * @param value the value to store or encode
     * @return this buffer for chaining
     */
    MessageBuffer<T> writeLong(long value);

    /**
     * Writes a 32-bit floating-point value and advances the writer position.
     *
     * @param value the value to store or encode
     * @return this buffer for chaining
     */
    MessageBuffer<T> writeFloat(float value);

    /**
     * Writes a 64-bit floating-point value and advances the writer position.
     *
     * @param value the value to store or encode
     * @return this buffer for chaining
     */
    MessageBuffer<T> writeDouble(double value);

    /**
     * Writes one implementation-framed string.
     *
     * @param message the message or string to send
     * @return this buffer for chaining
     */
    MessageBuffer<T> writeString(String message);

    /**
     * Writes framed strings consecutively.
     *
     * @param message the message or string to send
     * @return this buffer for chaining
     */
    MessageBuffer<T> writeStrings(String... message);

    /**
     * Converts an object and writes it using the implementation's object framing.
     *
     * @param <V> the converted value type
     * @param converter the converter used to transform the payload
     * @param object the object to convert or compare
     * @return this buffer for chaining
     */
    <V> MessageBuffer<T> writeObject(Transform<V, T> converter, V object);

    /**
     * Returns the underlying buffer without retaining it or transferring ownership.
     *
     * @return the native buffer
     */
    T getNativeBuffer();

    /**
     * Returns the native backing array when the implementation exposes one.
     *
     * @return the backing array, which may include bytes outside the readable region
     * @throws UnsupportedOperationException if the native buffer does not expose an array
     */
    byte[] array();

    /**
     * Resets reader and writer positions without releasing the native buffer or erasing its bytes.
     */
    void clear();
}
