package com.akakata.handlers;

import com.akakata.util.SmallFileReader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * @author Kelvin
 */
public class FlashPolicyServerHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger LOG = LoggerFactory.getLogger(FlashPolicyServerHandler.class);
    private static final String NEWLINE = "\r\n";
    /**
     * Flash policy file
     */
    private static ByteBuf policyFile;

    static {
        policyFile = null;
        String filePath = System.getProperty("flash.policy.file.path");
        if (filePath != null) {
            try {
                String fileContents = SmallFileReader.readSmallFile(filePath);
                policyFile = Unpooled.copiedBuffer(fileContents.getBytes());
            } catch (IOException e) {
                LOG.error("Unable to open flash policy file.", e);
            }
        }
    }

    /**
     * Flash policy server port
     */
    private final String portNumber;

    public FlashPolicyServerHandler(String portNumber) {
        this.portNumber = portNumber;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        ChannelFuture f;

        if (policyFile != null) {
            ByteBuf byteBuf = policyFile.copy();
            f = ctx.channel().writeAndFlush(byteBuf);
        } else {
            f = ctx.channel().writeAndFlush(this.getPolicyFileContents());
        }
        f.addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof ReadTimeoutException) {
            LOG.error("Connection timed out. Going to close channel.");
        } else {
            LOG.error("Exception in FlashPolicyFileHandler", cause);
        }
        ctx.channel().close();
    }

    private ByteBuf getPolicyFileContents() throws Exception {
        return Unpooled.copiedBuffer(
                "<?xml version=\"1.0\"?>" + NEWLINE +
                        "<!DOCTYPE cross-domain-policy SYSTEM \"/xml/dtds/cross-domain-policy.dtd\">" + NEWLINE +
                        "" + NEWLINE +
                        "<cross-domain-policy> " + NEWLINE +
                        "" + NEWLINE +
                        "   <site-control permitted-cross-domain-policies=\"master-only\"/>" + NEWLINE +
                        "" + NEWLINE +
                        "   <allow-access-from domain=\"*\" to-ports=\"" + portNumber + "\" />" + NEWLINE +
                        "" + NEWLINE +
                        "</cross-domain-policy>" + NEWLINE,
                CharsetUtil.US_ASCII);
    }
}
