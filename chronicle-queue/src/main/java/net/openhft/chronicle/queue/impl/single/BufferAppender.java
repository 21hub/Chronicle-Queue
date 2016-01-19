package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.ReadBytesMarshallable;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.impl.ringbuffer.BytesRingBuffer;
import net.openhft.chronicle.threads.HandlerPriority;
import net.openhft.chronicle.threads.api.EventHandler;
import net.openhft.chronicle.threads.api.EventLoop;
import net.openhft.chronicle.threads.api.InvalidEventHandlerException;
import net.openhft.chronicle.wire.Wire;
import net.openhft.chronicle.wire.WriteMarshallable;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

import static net.openhft.chronicle.bytes.NativeBytesStore.nativeStoreWithFixedCapacity;

/**
 * @author Rob Austin.
 */
public class BufferAppender implements ExcerptAppender {

    private final BytesRingBuffer ringBuffer;
    private final ExcerptAppender underlyingAppender;
    private final Wire wire;
    @NotNull
    private final EventLoop eventLoop;

    public BufferAppender(@NotNull final EventLoop eventLoop,
                          @NotNull final ExcerptAppender underlyingAppender,
                          final long ringBufferCapacity) {
        this.eventLoop = eventLoop;
        ringBuffer = new BytesRingBuffer(nativeStoreWithFixedCapacity(
                ringBufferCapacity));

        final ReadBytesMarshallable readBytesMarshallable = bytes -> {
            try {
                underlyingAppender.writeBytes(bytes);
            } catch (IOException e) {
                throw Jvm.rethrow(e);
            }
        };

        eventLoop.addHandler(() -> {
                        long size = ringBuffer.read(readBytesMarshallable);
                        return size > 0;
                }
        );

        eventLoop.addHandler(new EventHandler() {
            @Override
            public boolean action() throws InvalidEventHandlerException {
                long writeBytesRemaining = ringBuffer
                        .minNumberOfWriteBytesRemainingSinceLastCall();


                // the capacity1 is slightly less than the memory allocated to the ring
                // as the ring buffer itself uses some memory for the header
                final long capacity1 = ringBuffer.capacity();

                final double percentage = ((double) writeBytesRemaining / (double)
                        capacity1) * 100;
                System.out.println("ring buffer=" + (capacity1 - writeBytesRemaining) / 1024 +
                        "KB/" + capacity1 / 1024 + "KB [" + (int) percentage + "% FreeSpace]");


                return true;
            }

            @NotNull
            @Override
            public HandlerPriority priority() {
                return HandlerPriority.MONITOR;
            }
        });

        eventLoop.start();

        this.underlyingAppender = underlyingAppender;
        this.wire = underlyingAppender.queue().wireType().apply(Bytes.elasticByteBuffer());
    }

    @Override
    public long writeDocument(@NotNull WriteMarshallable writer) throws IOException {
        final Bytes<?> bytes = wire.bytes();
        bytes.clear();
        writer.writeMarshallable(wire);
        return writeBytes(bytes);
    }

    @Override
    public long writeBytes(@NotNull WriteBytesMarshallable marshallable) throws IOException {
        final Bytes<?> bytes = wire.bytes();
        bytes.clear();
        marshallable.writeMarshallable(bytes);
        return writeBytes(bytes);
    }

    @Override
    public long writeBytes(@NotNull Bytes<?> bytes) throws IOException {
        try {
            ringBuffer.offer(bytes);
            eventLoop.unpause();
        } catch (InterruptedException e) {
            throw Jvm.rethrow(e);
        }

        return -1;
    }

    @Override
    public long index() {
        throw new UnsupportedOperationException("");
    }

    @Override
    public long cycle() {
        return underlyingAppender.cycle();
    }

    @Override
    public ExcerptAppender underlying() {
        return underlyingAppender;
    }

    @Override
    public ChronicleQueue queue() {
        throw new UnsupportedOperationException("");
    }

    @Override
    public void prefetch() {
    }

}
