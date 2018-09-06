package com.google.flatbuffers;

import static com.google.flatbuffers.FlatBufferBuilder.growByteBuffer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.function.Consumer;

/**
 * A special FlatBufferBuilder able to write to Netty ByteBufs
 *
 * @author Enrico Olivelli
 */
public final class ByteBufFlatBufferBuilder extends FlatBufferBuilder {

    private static final int INITIAL_BUFFER_SIZE = 1024;
    private static final ByteBuffer DUMMY_BUFFER = ByteBuffer.wrap(new byte[0]);

    private final Consumer<ByteBuffer> byteBufferReleaser;
    private final IdentityHashMap<ByteBuffer, ByteBuf> byteBufferToByteBufMapping = new IdentityHashMap<>();

    public ByteBufFlatBufferBuilder() {
        super(DUMMY_BUFFER, null); // this is needed

        init(allocateNewByteBuffer(INITIAL_BUFFER_SIZE), this::allocateNewByteBuffer);
        this.byteBufferReleaser = this::releaseByteBuffer;
    }

    /**
     * This copied code should be removed as soon as we upgrade to FlatBuffers
     * 1.10.0 which has an enhanced ByteBufferFactory which allows to 'release'
     * the buffer
     */
    @Override
    public void prep(int size, int additional_bytes) {
        // Track the biggest thing we've ever aligned to.
        if (size > minalign) {
            minalign = size;
        }
        // Find the amount of alignment needed such that `size` is properly
        // aligned after `additional_bytes`
        int align_size = ((~(bb.capacity() - space + additional_bytes)) + 1) & (size - 1);
        // Reallocate the buffer if needed.
        while (space < align_size + size + additional_bytes) {
            int old_buf_size = bb.capacity();
            ByteBuffer prev = bb;
            bb = growByteBuffer(prev, bb_factory);
            if (bb != prev) {
                // RELEASE PREV MEMORY
                // this will be ByteBufferFactory#releaseByteBuffer in FB 1.10.0
                byteBufferReleaser.accept(prev);
            }
            space += bb.capacity() - old_buf_size;
        }
        pad(align_size);
    }

    private ByteBuffer allocateNewByteBuffer(int size) {
        ByteBuf byteBuf
                = PooledByteBufAllocator.DEFAULT.directBuffer(size);
        // this an hack !
        // ByteBuf.nioBuffer() will return a view over the 'readable'
        // portion of the ByteBuf, that is from readerIndex() to writerIndex()
        byteBuf.writerIndex(size);
        ByteBuffer byteBuffer = byteBuf
                .nioBuffer()
                .order(ByteOrder.LITTLE_ENDIAN); // this is needed by FlatBuffers

        byteBufferToByteBufMapping.put(byteBuffer, byteBuf);

        return byteBuffer;
    }

    public static ByteBufFlatBufferBuilder newFlatBufferBuilder() {
        return new ByteBufFlatBufferBuilder();
    }

    private void releaseByteBuffer(ByteBuffer byteBuffer) {
        ByteBuf original = byteBufferToByteBufMapping.remove(byteBuffer);
        original.release();
    }

    public ByteBuf toByteBuf() {
        ByteBuf byteBuf = byteBufferToByteBufMapping.remove(dataBuffer());
        // buffer is filled from the end to the beginnning
        byteBuf.readerIndex(bb.position());

        bb = null;
        if (!byteBufferToByteBufMapping.isEmpty()) {
            throw new IllegalStateException();
        }

        return byteBuf;
    }

}
