package edu.cufe.auction.world.bankprobe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试专用信道：把真实 {@link FileChannel} 包一层，在指定次数后对 {@code write} 或 {@code force}
 * 抛 {@link IOException}，用来验证世界日志提交失败时内存与磁盘都不半应用。
 *
 * <p>只有测试使用；生产代码不知道它的存在，也不含任何失败注入开关。</p>
 */
final class FailingChannel extends FileChannel {

    /** 注入位置。 */
    enum Mode {
        /** 写正文时失败（帧未落地）。 */ WRITE,
        /** 写完后 force 失败（字节可能已落地但未确认）。 */ FORCE
    }

    private final FileChannel delegate;
    private final Mode mode;
    private final AtomicInteger armAfter;

    FailingChannel(FileChannel delegate, Mode mode, int armAfter) {
        this.delegate = delegate;
        this.mode = mode;
        this.armAfter = new AtomicInteger(armAfter);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return delegate.read(dst);
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        return delegate.read(dsts, offset, length);
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
        return delegate.read(dst, position);
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        return delegate.write(srcs, offset, length);
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
        return delegate.write(src, position);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (mode == Mode.WRITE && armed()) {
            throw new IOException("INJECTED_WRITE_FAILURE");
        }
        return delegate.write(src);
    }

    @Override
    public long position() throws IOException {
        return delegate.position();
    }

    @Override
    public FileChannel position(long newPosition) throws IOException {
        delegate.position(newPosition);
        return this;
    }

    @Override
    public long size() throws IOException {
        return delegate.size();
    }

    @Override
    public FileChannel truncate(long size) throws IOException {
        delegate.truncate(size);
        return this;
    }

    @Override
    public void force(boolean metaData) throws IOException {
        if (mode == Mode.FORCE && armed()) {
            throw new IOException("INJECTED_FORCE_FAILURE");
        }
        delegate.force(metaData);
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target)
            throws IOException {
        return delegate.transferTo(position, count, target);
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count)
            throws IOException {
        return delegate.transferFrom(src, position, count);
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
        return delegate.map(mode, position, size);
    }

    @Override
    public FileLock lock(long position, long size, boolean shared) throws IOException {
        return delegate.lock(position, size, shared);
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared) throws IOException {
        return delegate.tryLock(position, size, shared);
    }

    @Override
    protected void implCloseChannel() throws IOException {
        delegate.close();
    }

    private boolean armed() {
        int left = armAfter.decrementAndGet();
        return left <= 0;
    }
}
