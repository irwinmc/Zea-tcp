package com.akakata.server.http;

import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies static path boundaries and file-handle cleanup independently of application state.
 */
class StaticResourceRegressionTest {

    /**
     * Trusted temporary root and synthetic outside resources.
     */
    @TempDir
    Path directory;

    /**
     * Creates the static resource fixture.
     */
    StaticResourceRegressionTest() {
    }

    /**
     * Opens decoded names with query strings and closes the owned handle.
     *
     * @throws Exception if fixture I/O fails
     */
    @Test
    void containedFileSupportsEncodedNamesAndQueryStrings() throws Exception {
        Path root = Files.createDirectory(directory.resolve("html"));
        byte[] expected = "owned static data".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("hello+ world-你好.txt"), expected);
        StaticResourceResolver resolver = new StaticResourceResolver(root);
        StaticResourceResolver.Resource resource = resolver.open("/hello+%20world-%E4%BD%A0%E5%A5%BD.txt?version=1");
        try (resource) {
            assertEquals(expected.length, resource.length());
            ByteBuffer data = ByteBuffer.allocate(expected.length);
            while (data.hasRemaining()) {
                assertTrue(resource.channel().read(data) >= 0);
            }
            assertArrayEquals(expected, data.array());
        }
        assertFalse(resource.channel().isOpen());
    }

    /**
     * Rejects traversal and hidden paths before opening any resource.
     *
     * @param uri forbidden request path
     * @throws Exception if fixture setup fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"/../outside.txt", "/%2e%2e/outside.txt", "/safe/../../outside.txt",
            "/.hidden", "/safe/.hidden/file", "/safe/%2e%2e/file", "/%2foutside.txt", "/safe%5c..%5coutside.txt", "/%00"})
    void rejectsForbiddenPaths(String uri) throws Exception {
        Path root = Files.createDirectory(directory.resolve("html"));
        assertThrows(AccessDeniedException.class, () -> new StaticResourceResolver(root).open(uri));
    }

    /**
     * Rejects both file and directory links pointing outside the trusted root.
     *
     * @throws Exception if fixture I/O fails
     */
    @Test
    void rejectsOutsideFileAndDirectorySymbolicLinks() throws Exception {
        Path root = Files.createDirectory(directory.resolve("html"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("fixture.txt"), "synthetic outside data");
        Files.createSymbolicLink(root.resolve("file-link.txt"), outside.resolve("fixture.txt"));
        Files.createSymbolicLink(root.resolve("directory-link"), outside);
        StaticResourceResolver resolver = new StaticResourceResolver(root);
        assertThrows(AccessDeniedException.class, () -> resolver.open("/file-link.txt"));
        assertThrows(AccessDeniedException.class, () -> resolver.open("/directory-link/fixture.txt"));
    }

    /**
     * Applies the no-link policy to contained links and the configured root itself.
     *
     * @throws Exception if fixture I/O fails
     */
    @Test
    void rejectsContainedLinksAndLinkedRoots() throws Exception {
        Path root = Files.createDirectory(directory.resolve("html"));
        Files.writeString(root.resolve("fixture.txt"), "owned static data");
        Files.createSymbolicLink(root.resolve("inside-link.txt"), root.resolve("fixture.txt"));
        assertThrows(AccessDeniedException.class, () -> new StaticResourceResolver(root).open("/inside-link.txt"));
        Path linkedRoot = Files.createSymbolicLink(directory.resolve("linked-root"), root);
        assertThrows(AccessDeniedException.class, () -> new StaticResourceResolver(linkedRoot).open("/fixture.txt"));
    }

    /**
     * Closes the opened handle when a file-region write is rejected.
     *
     * @throws Exception if fixture I/O fails
     */
    @Test
    void rejectedTransferReleasesFileRegionAndHandle() throws Exception {
        Path root = Files.createDirectory(directory.resolve("html"));
        Files.writeString(root.resolve("fixture.txt"), "owned static data");
        StaticFileHandler handler = new StaticFileHandler(new StaticResourceResolver(root));
        AtomicReference<DefaultFileRegion> captured = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                if (message instanceof DefaultFileRegion region) {
                    captured.set(region);
                    ReferenceCountUtil.release(message);
                    promise.setFailure(new IOException("Injected file transfer failure"));
                } else {
                    context.write(message, promise);
                }
            }
        }, route(handler));
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/fixture.txt"));
            assertNotNull(captured.get());
            assertEquals(0, captured.get().refCnt());
            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Handles conditional and malformed cache headers without retaining a transfer handle.
     *
     * @throws Exception if fixture I/O fails
     */
    @Test
    void conditionalRequestsAndInvalidDatesHaveDeterministicStatus() throws Exception {
        Path root = Files.createDirectory(directory.resolve("html"));
        Files.writeString(root.resolve("fixture.txt"), "owned static data");
        StaticFileHandler handler = new StaticFileHandler(new StaticResourceResolver(root));
        EmbeddedChannel cached = new EmbeddedChannel(route(handler));
        EmbeddedChannel invalid = new EmbeddedChannel(route(handler));
        try {
            var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/fixture.txt?cache=1");
            request.headers().set("If-Modified-Since", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().plusDays(1)));
            cached.writeInbound(request);
            FullHttpResponse response = cached.readOutbound();
            try {
                assertEquals(304, response.status().code());
            } finally {
                response.release();
            }
            var malformed = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/fixture.txt");
            malformed.headers().set("If-Modified-Since", "invalid-date");
            invalid.writeInbound(malformed);
            FullHttpResponse failure = invalid.readOutbound();
            try {
                assertEquals(400, failure.status().code());
            } finally {
                failure.release();
            }
        } finally {
            cached.finishAndReleaseAll();
            invalid.finishAndReleaseAll();
        }
    }

    /**
     * Returns HTTP 403 for an outside symlink rather than a successful file response.
     *
     * @throws Exception if fixture I/O fails
     */
    @Test
    void httpBoundaryRejectsOutsideSymlink() throws Exception {
        Path root = Files.createDirectory(directory.resolve("html"));
        Path outside = Files.writeString(directory.resolve("outside.txt"), "synthetic outside data");
        Files.createSymbolicLink(root.resolve("link.txt"), outside);
        EmbeddedChannel channel = new EmbeddedChannel(route(new StaticFileHandler(new StaticResourceResolver(root))));
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/link.txt"));
            FullHttpResponse response = channel.readOutbound();
            try {
                assertEquals(403, response.status().code());
            } finally {
                response.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Adapts borrowed requests to the static handler while retaining normal Netty ownership.
     *
     * @param handler static presenter
     * @return per-channel route
     */
    private static SimpleChannelInboundHandler<FullHttpRequest> route(StaticFileHandler handler) {
        return new SimpleChannelInboundHandler<>() {
            @Override
            protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) throws Exception {
                handler.handleStaticFile(context, request);
            }
        };
    }
}
