package com.akakata.server.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Wraps an actual file while allowing a test to gate or fail its close operation.
 */
final class ObservedFileChannel extends FileChannel {

    /**
     * Actual descriptor delegated by this wrapper.
     */
    private final FileChannel file;

    /**
     * Deterministic close observation and continuation gate.
     */
    private final Runnable beforeClose;

    /**
     * Injects a close failure after the actual descriptor has been closed.
     */
    private final boolean failClose;

    /**
     * Captures the real file and its close probe.
     *
     * @param file actual owned file
     * @param beforeClose close observer
     * @param failClose whether to throw after closing
     */
    ObservedFileChannel(FileChannel file, Runnable beforeClose, boolean failClose) {
        this.file = file;
        this.beforeClose = beforeClose;
        this.failClose = failClose;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void implCloseChannel() throws IOException {
        try {
            beforeClose.run();
        } finally {
            file.close();
        }
        if (failClose) {
            throw new IOException("Injected file close failure");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(ByteBuffer target) throws IOException {
        return file.read(target);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long read(ByteBuffer[] targets, int offset, int length) throws IOException {
        return file.read(targets, offset, length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int write(ByteBuffer source) throws IOException {
        return file.write(source);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long write(ByteBuffer[] sources, int offset, int length) throws IOException {
        return file.write(sources, offset, length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long position() throws IOException {
        return file.position();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FileChannel position(long position) throws IOException {
        file.position(position);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long size() throws IOException {
        return file.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FileChannel truncate(long size) throws IOException {
        file.truncate(size);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void force(boolean metadata) throws IOException {
        file.force(metadata);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
        return file.transferTo(position, count, target);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long transferFrom(ReadableByteChannel source, long position, long count) throws IOException {
        return file.transferFrom(source, position, count);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(ByteBuffer target, long position) throws IOException {
        return file.read(target, position);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int write(ByteBuffer source, long position) throws IOException {
        return file.write(source, position);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
        return file.map(mode, position, size);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FileLock lock(long position, long size, boolean shared) throws IOException {
        return file.lock(position, size, shared);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FileLock tryLock(long position, long size, boolean shared) throws IOException {
        return file.tryLock(position, size, shared);
    }
}
