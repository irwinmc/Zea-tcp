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
import java.util.NoSuchElementException;

/**
 * @author Kelvin
 */
public class NettyUtils {

    public static final String NETTY_CHANNEL = "NETTY_CHANNEL";
    private static final Logger LOG = LoggerFactory.getLogger(NettyUtils.class);

    public static ChannelPipeline getPipeLineOfConnection(SocketMessageSender messageSender) {
        if (messageSender != null) {
            Channel channel = messageSender.getChannel();
            return channel.pipeline();
        }
        return null;
    }

    public static ChannelPipeline getPipeLineOfConnection(PlayerSession playerSession) {
        return getPipeLineOfConnection((SocketMessageSender) playerSession.getSender());
    }

    /**
     * A utility method to clear the netty pipeline of all handlers.
     *
     * @param pipeline
     */
    public static void clearPipeline(ChannelPipeline pipeline) {
        if (pipeline == null) {
            return;
        }
        try {
            int counter = 0;

            while (pipeline.first() != null) {
                pipeline.removeFirst();
                counter++;
            }
            LOG.debug("Removed {} handlers from pipeline", counter);
        } catch (NoSuchElementException e) {
            // all elements removed.
        }
    }

    public static ByteBuf createBufferForOpcode(int opcode) {
        ByteBuf buffer = Unpooled.buffer(1);
        buffer.writeByte(opcode);
        return buffer;
    }

    public static String[] readStrings(ByteBuf buffer, int numOfStrings) {
        return readStrings(buffer, numOfStrings, CharsetUtil.UTF_8);
    }

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

    public static String readString(ByteBuf buffer) {
        return readString(buffer, CharsetUtil.UTF_8);
    }

    public static String readString(ByteBuf buffer, Charset charset) {
        String readString = null;
        if (buffer != null && buffer.readableBytes() > 4) {
            int length = buffer.readInt();
            readString = readString(buffer, length, charset);
        }
        return readString;
    }

    public static String readString(ByteBuf buffer, int length) {
        return readString(buffer, length, CharsetUtil.UTF_8);
//		char[] chars = new char[length];
//		for (int i = 0; i < length; i++)
//		{
//			chars[i] = buffer.readChar();
//		}
//		return new String(chars);
    }

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

    public static ByteBuf writeStrings(String... msgs) {
        return writeStrings(CharsetUtil.UTF_8, msgs);
    }

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

    public static ByteBuf writeString(String msg) {
        return writeString(msg, CharsetUtil.UTF_8);
    }

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
     * converts a bytebuf to byte array.
     *
     * @param buf
     * @param isReadDestroy if true then the reader index of the bytebuf will be modified.
     * @return
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
