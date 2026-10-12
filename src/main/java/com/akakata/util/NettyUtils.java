package com.akakata.util;

import com.akakata.app.PlayerSession;
import com.akakata.communication.Transform;
import com.akakata.communication.impl.SocketMessageSender;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;

/**
 * Provides Netty pipeline helpers and binary string or object framing.
 *
 * <p>String frames use a four-byte length followed by encoded bytes. Object frames
 * use a two-byte unsigned length followed by converter output. Returned native
 * buffers are owned by the caller and must be released or transferred to a pipeline.
 *
 * @author Kelvin
 */
public class NettyUtils {

    /**
     * Creates a netty utils instance.
     */
    public NettyUtils() {
    }

    /**
     * Attribute key used to associate a Netty channel with application data.
     */
    public static final String NETTY_CHANNEL = "NETTY_CHANNEL";

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(NettyUtils.class);

    /**
     * Returns the pipeline attached to the session's socket sender or to the supplied socket
     * sender.
     *
     * @param messageSender the message sender
     * @return the channel pipeline, or {@code null} when no sender is attached
     */
    public static ChannelPipeline getPipeLineOfConnection(SocketMessageSender messageSender) {
        if (messageSender != null) {
            Channel channel = messageSender.getChannel();
            return channel.pipeline();
        }
        return null;
    }

    /**
     * Returns the pipeline attached to the session's socket sender or to the supplied socket
     * sender.
     *
     * @param playerSession the player session
     * @return the channel pipeline, or {@code null} when no sender is attached
     */
    public static ChannelPipeline getPipeLineOfConnection(PlayerSession playerSession) {
        return getPipeLineOfConnection((SocketMessageSender) playerSession.getSender());
    }

    /**
     * Removes protocol handlers while preserving installed transport lifetime and outbound guards.
     * A null pipeline is ignored.
     *
     * @param pipeline the Netty handler pipeline
     */
    public static void clearPipeline(ChannelPipeline pipeline) {
        if (pipeline == null) {
            return;
        }
        for (String name : java.util.List.copyOf(pipeline.names())) {
            if (pipeline.get(name) != null && !name.equals("zeaReadIdle")
                    && !(pipeline.get(name) instanceof com.akakata.handlers.ConnectionScopedHandler)) {
                pipeline.remove(name);
            }
        }
    }

    /**
     * Allocates a one-byte opcode buffer. The caller owns the returned reference.
     *
     * @param opcode the event opcode written in the low eight bits
     * @return the allocated opcode buffer
     */
    public static ByteBuf createBufferForOpcode(int opcode) {
        ByteBuf buffer = Unpooled.buffer(1);
        buffer.writeByte(opcode);
        return buffer;
    }

    /**
     * Reads framed strings into an array until a null result is encountered.
     *
     * <p>The current implementation reads individual strings as UTF-8 even in the
     * overload accepting a charset. Remaining array entries stay null.
     *
     * @param buffer       the caller-owned source buffer
     * @param numOfStrings the requested number of strings
     * @return the decoded string array
     */
    public static String[] readStrings(ByteBuf buffer, int numOfStrings) {
        return readStrings(buffer, numOfStrings, CharsetUtil.UTF_8);
    }

    /**
     * Reads framed strings into an array until a null result is encountered.
     *
     * <p>The current implementation reads individual strings as UTF-8 even in the
     * overload accepting a charset. Remaining array entries stay null.
     *
     * @param buffer       the caller-owned source buffer
     * @param numOfStrings the requested number of strings
     * @param charset      the character encoding; null selects UTF-8 where supported
     * @return the decoded string array
     */
    public static String[] readStrings(ByteBuf buffer, int numOfStrings, Charset charset) {
        String[] strings = new String[numOfStrings];
        for (int i = 0; i < numOfStrings; i++) {
            String theStr = readString(buffer);
            if (theStr == null) {
                break;
            }
            strings[i] = theStr;
        }
        return strings;
    }

    /**
     * Reads a string at the current reader position.
     *
     * <p>Overloads without an explicit length first read a four-byte length prefix and
     * return null when no buffer is supplied or at most four bytes remain. Overloads
     * with a length decode exactly that many bytes. UTF-8 is used unless a charset is
     * supplied; null charsets also select UTF-8. Slice conversion failures are logged.
     *
     * @param buffer the caller-owned source buffer
     * @return the decoded string, or {@code null} if no framed string can be decoded
     */
    public static String readString(ByteBuf buffer) {
        return readString(buffer, CharsetUtil.UTF_8);
    }

    /**
     * Reads a string at the current reader position.
     *
     * <p>Overloads without an explicit length first read a four-byte length prefix and
     * return null when no buffer is supplied or at most four bytes remain. Overloads
     * with a length decode exactly that many bytes. UTF-8 is used unless a charset is
     * supplied; null charsets also select UTF-8. Slice conversion failures are logged.
     *
     * @param buffer  the caller-owned source buffer
     * @param charset the character encoding; null selects UTF-8 where supported
     * @return the decoded string, or {@code null} if no framed string can be decoded
     */
    public static String readString(ByteBuf buffer, Charset charset) {
        String readString = null;
        if (buffer != null && buffer.readableBytes() > 4) {
            int length = buffer.readInt();
            readString = readString(buffer, length, charset);
        }
        return readString;
    }

    /**
     * Reads a string at the current reader position.
     *
     * <p>Overloads without an explicit length first read a four-byte length prefix and
     * return null when no buffer is supplied or at most four bytes remain. Overloads
     * with a length decode exactly that many bytes. UTF-8 is used unless a charset is
     * supplied; null charsets also select UTF-8. Slice conversion failures are logged.
     *
     * @param buffer the caller-owned source buffer
     * @param length the number of bytes or characters requested
     * @return the decoded string, or {@code null} if no framed string can be decoded
     */
    public static String readString(ByteBuf buffer, int length) {
        return readString(buffer, length, CharsetUtil.UTF_8);
//		char[] chars = new char[length];
//		for (int i = 0; i < length; i++)
//		{
//			chars[i] = buffer.readChar();
//		}
//		return new String(chars);
    }

    /**
     * Reads a string at the current reader position.
     *
     * <p>Overloads without an explicit length first read a four-byte length prefix and
     * return null when no buffer is supplied or at most four bytes remain. Overloads
     * with a length decode exactly that many bytes. UTF-8 is used unless a charset is
     * supplied; null charsets also select UTF-8. Slice conversion failures are logged.
     *
     * @param buffer  the caller-owned source buffer
     * @param length  the number of bytes or characters requested
     * @param charset the character encoding; null selects UTF-8 where supported
     * @return the decoded string, or {@code null} if no framed string can be decoded
     */
    public static String readString(ByteBuf buffer, int length, Charset charset) {
        String str = null;
        if (charset == null) {
            charset = CharsetUtil.UTF_8;
        }
        try {
            ByteBuf stringBuffer = buffer.readSlice(length);
            str = stringBuffer.toString(charset);
        } catch (Exception e) {
            LOG.error("Error occurred while trying to read string from buffer: {}", e);
        }
        return str;
    }

    /**
     * Concatenates framed strings into a caller-owned buffer.
     *
     * <p>In the charset overload, the first string uses the supplied charset and later
     * strings use UTF-8 in the current implementation.
     *
     * @param msgs the strings to encode
     * @return the concatenated buffer, or {@code null} for no strings or an encoding failure
     */
    public static ByteBuf writeStrings(String... msgs) {
        return writeStrings(CharsetUtil.UTF_8, msgs);
    }

    /**
     * Concatenates framed strings into a caller-owned buffer.
     *
     * <p>In the charset overload, the first string uses the supplied charset and later
     * strings use UTF-8 in the current implementation.
     *
     * @param charset the character encoding; null selects UTF-8 where supported
     * @param msgs    the strings to encode
     * @return the concatenated buffer, or {@code null} for no strings or an encoding failure
     */
    public static ByteBuf writeStrings(Charset charset, String... msgs) {
        ByteBuf buffer = null;
        for (String msg : msgs) {
            if (buffer == null) {
                buffer = writeString(msg, charset);
            } else {
                ByteBuf theBuffer = writeString(msg);
                if (theBuffer != null) {
                    buffer = Unpooled.wrappedBuffer(buffer, theBuffer);
                }
            }
        }
        return buffer;
    }

    /**
     * Encodes a four-byte length followed by string bytes.
     *
     * <p>UTF-8 is used by default and for a null charset. The caller owns the returned
     * buffer and must release it or transfer it to a pipeline. Encoding failures are logged.
     *
     * @param msg the message or buffer being processed
     * @return the allocated framed buffer, or {@code null} if encoding fails
     */
    public static ByteBuf writeString(String msg) {
        return writeString(msg, CharsetUtil.UTF_8);
    }

    /**
     * Encodes a four-byte length followed by string bytes.
     *
     * <p>UTF-8 is used by default and for a null charset. The caller owns the returned
     * buffer and must release it or transfer it to a pipeline. Encoding failures are logged.
     *
     * @param msg     the message or buffer being processed
     * @param charset the character encoding; null selects UTF-8 where supported
     * @return the allocated framed buffer, or {@code null} if encoding fails
     */
    public static ByteBuf writeString(String msg, Charset charset) {
        ByteBuf buffer = null;
        try {
            ByteBuf stringBuffer;
            if (charset == null) {
                charset = CharsetUtil.UTF_8;
            }
            stringBuffer = Unpooled.copiedBuffer(msg, charset);
            int length = stringBuffer.readableBytes();
            ByteBuf lengthBuffer = Unpooled.buffer(2);
            lengthBuffer.writeInt(length);
            buffer = Unpooled.wrappedBuffer(lengthBuffer, stringBuffer);
        } catch (Exception e) {
            LOG.error("Error occurred while trying to write string buffer: {}", e);
        }
        return buffer;
    }

    /**
     * Reads a two-byte unsigned length and converts a borrowed slice of that size.
     *
     * <p>The converter must retain a native slice that it keeps beyond the caller's
     * buffer lifetime. Conversion failures are logged and return null.
     *
     * @param <T>     an unused type parameter retained by the current helper signature
     * @param <V>     the converted value type
     * @param buffer  the caller-owned source buffer
     * @param decoder the inbound decoder or object converter
     * @return the decoded object, or {@code null} if framing or conversion fails
     */
    public static <T, V> V readObject(ByteBuf buffer, Transform<ByteBuf, V> decoder) {
        int length = 0;
        if (buffer != null && buffer.readableBytes() > 2) {
            length = buffer.readUnsignedShort();
        } else {
            return null;
        }

        ByteBuf objBuffer = buffer.readSlice(length);
        V obj = null;
        try {
            obj = decoder.convert(objBuffer);
        } catch (Exception e) {
            LOG.error("Error occurred while trying to read object from buffer: {}", e);
        }
        return obj;
    }

    /**
     * Converts an object and prepends its payload with a two-byte length.
     *
     * <p>The returned composite owns the converter's buffer reference. The caller must
     * release the result or transfer it to a pipeline. Conversion failures are logged.
     *
     * @param <V>     the converted value type
     * @param encoder the outbound encoder or object converter
     * @param object  the object to convert or compare
     * @return the caller-owned framed buffer, or {@code null} if encoding fails
     */
    public static <V> ByteBuf writeObject(Transform<V, ByteBuf> encoder, V object) {
        ByteBuf buffer = null;
        try {
            ByteBuf objectBuffer = encoder.convert(object);
            int length = objectBuffer.readableBytes();
            ByteBuf lengthBuffer = Unpooled.buffer(2);
            lengthBuffer.writeShort(length);
            buffer = Unpooled.wrappedBuffer(lengthBuffer, objectBuffer);
        } catch (Exception e) {
            LOG.error("Error occurred while writing object to buffer: {}", e);
        }
        return buffer;
    }

    /**
     * Copies the readable region into a new array.
     *
     * <p>When requested, advances the reader index by the copied size; it does not
     * release or destroy the native buffer.
     *
     * @param buf           the source native buffer
     * @param isReadDestroy whether to advance the source reader index while copying
     * @return the copied readable bytes
     */
    public static byte[] toByteArray(ByteBuf buf, boolean isReadDestroy) {
        byte[] arr = new byte[buf.readableBytes()];
        if (isReadDestroy) {
            buf.readBytes(arr);
        } else {
            buf.getBytes(buf.readerIndex(), arr);
        }
        return arr;
    }
}
