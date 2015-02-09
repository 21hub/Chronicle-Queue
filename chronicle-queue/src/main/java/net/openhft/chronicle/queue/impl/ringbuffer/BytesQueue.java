package net.openhft.chronicle.queue.impl.ringbuffer;

import net.openhft.lang.io.Bytes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Rob Austin.
 */
public class BytesQueue {

    private static final Logger LOG = LoggerFactory.getLogger(BytesQueue.class.getName());

    private static final int SIZE_OF_SIZE = 8;

    @NotNull
    private final BytesWriter writer;
    private final BytesReader reader;

    public BytesQueue(@NotNull final Bytes buffer) {
        writer = new BytesWriter(buffer);
        reader = new BytesReader(buffer);
        writeupto = new AtomicLong(buffer.capacity());
    }


    long remainingForWrite(long offset) {
        return (writeupto.get() - 1) - offset;

    }

    enum States {BUSY, READY, USED}


    /**
     * Inserts the specified element at the tail of this queue if it is possible to do so
     * immediately without exceeding the queue's capacity, returning {@code true} upon success and
     * {@code false} if this queue is full.
     *
     * @InterruptedException if interrupted
     */
    public boolean offer(@NotNull Bytes bytes) throws InterruptedException {


        try {

            for (; ; ) {

                long writeLocation = this.writeLocation();

                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException();


                // if reading is occurring the remain capacity will only get larger, as have locked
                if (remainingForWrite(writeLocation) < bytes.remaining() + SIZE_OF_SIZE) {

                    if (freeReadMessages()) {
                        if (remainingForWrite(writeLocation) < bytes.remaining() + SIZE_OF_SIZE)
                            return false;
                    } else
                        return false;
                }

                // write the size
                long len = bytes.remaining();

                long messageLen = 1 + 8 + len;

                long offset = writeLocation;

                // we want to ensure that only one thread ever gets in here at a time
                if (!this.writeLocation.compareAndSet(writeLocation, -1))
                    continue;

                // we have to set the busy fag before the write location for the reader
                // this is why we have the compareAndSet above to ensure that only one thread
                // gets in here
                long flagLoc = offset;
                offset = writer.writeByte(offset, States.BUSY.ordinal());

                if (!this.writeLocation.compareAndSet(-1, writeLocation + messageLen))
                    continue;


                // write a size
                offset = writer.write(offset, len);

                // write the data
                writer.write(offset, bytes);

                writer.writeByte(flagLoc, States.READY.ordinal());
                return true;

            }

        } catch (IllegalStateException e) {
            LOG.error("", e);
            // when the queue is full
            return false;
        }
    }

    boolean freeReadMessages() {
        boolean success = false;
        long start;
        long offset = start = writeupto.get();

        while (reader.readByte(offset) == States.USED.ordinal() && offset < writeLocation()) {
            offset += reader.readLong(offset);
            success = true;
        }

        if (success)
            writeupto.compareAndSet(start, offset + writer.capacity());


        return success;
    }

    long writeLocation() {

        long writeLocation;

        while ((writeLocation = this.writeLocation.get()) == -1) {
            ;
        }

        return writeLocation;
    }

    /**
     * Retrieves and removes the head of this queue, or returns {@code null} if this queue is
     * empty.
     *
     * @return the head of this queue, or {@code null} if this queue is empty
     * @throws IllegalStateException is the {@code using} buffer is not large enough
     */
    @Nullable
    public Bytes poll(@NotNull Bytes using) throws InterruptedException, IllegalStateException {


        for (; ; ) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException();

            long offset;
            long readLocation = offset = this.readLocation.get();

            if (readLocation >= writeLocation()) {
                return null;
            }

            assert readLocation <= writeLocation() : "reader has go ahead of the writer";


            long flag = offset;
            byte state = reader.readByte(flag);

            // the element is currently being written to, so let wait for the write to finish
            if (state == States.BUSY.ordinal()) continue;

            assert state == States.READY.ordinal() : " we are reading a message that we " +
                    "shouldn't,  state=" + state;

            offset += 1;

            long elementSize = reader.readLong(offset);
            offset += 8;

            if (!this.readLocation.compareAndSet(readLocation, offset + elementSize))
                continue;

            // if the element has already been read by anther thread it will USED
            if (state == States.USED.ordinal()) continue;


            // checks that the 'using' bytes is large enough
            checkSize(using, elementSize);


            long position = using.position();
            using.limit(position + elementSize);


            reader.read(using, offset);

            writer.write(flag, States.USED.ordinal());


            writeupto.compareAndSet(readLocation + writer.capacity(), offset + writer.capacity() + elementSize);

            using.position(position);
            return using;

        }
    }


    private static void checkSize(@NotNull Bytes using, long elementSize) {
        if (using.remaining() < elementSize)
            throw new IllegalStateException("requires size=" + elementSize + " " +
                    "bytes, but " +
                    "only " + using.remaining() + " remaining.");
    }


    final AtomicLong readLocation = new AtomicLong();
    final AtomicLong writeLocation = new AtomicLong();
    final AtomicLong writeupto;


    private abstract class AbstractBytesWriter {

        final Bytes buffer;
        boolean isBytesBigEndian;

        public AbstractBytesWriter(@NotNull Bytes buffer) {
            this.buffer = buffer;
            isBytesBigEndian = isBytesBigEndian();
        }

        long nextOffset(long offset, long increment) {

            long result = offset + increment;
            if (result < capacity())
                return result;

            return result % capacity();

        }

        long nextOffset(long offset) {
            return nextOffset(offset, 1);
        }

        long capacity() {
            return buffer.capacity();
        }

        boolean isBytesBigEndian() {
            try {
                putLongB(0, 1);
                return buffer.flip().readLong() == 1;
            } finally {
                buffer.clear();
            }
        }

        void putLongB(long offset, long value) {
            buffer.writeByte(offset, (byte) (value >> 56));
            buffer.writeByte(offset = nextOffset(offset), (byte) (value >> 48));
            buffer.writeByte(offset = nextOffset(offset), (byte) (value >> 40));
            buffer.writeByte(offset = nextOffset(offset), (byte) (value >> 32));
            buffer.writeByte(offset = nextOffset(offset), (byte) (value >> 24));
            buffer.writeByte(offset = nextOffset(offset), (byte) (value >> 16));
            buffer.writeByte(offset = nextOffset(offset), (byte) (value >> 8));
            buffer.writeByte(nextOffset(offset), (byte) (value));

        }

        void putLongL(long offset, long value) {
            buffer.writeByte(offset, (byte) (value));
            buffer.writeByte(offset = nextOffset(offset), (byte) (value >> 8));
            buffer.writeByte(offset = nextOffset(offset), (byte) (value >> 16));
            buffer.writeByte(offset = nextOffset(offset), (byte) (value >> 24));
            buffer.writeByte(offset = nextOffset(offset), (byte) (value >> 32));
            buffer.writeByte(offset = nextOffset(offset), (byte) (value >> 40));
            buffer.writeByte(offset = nextOffset(offset), (byte) (value >> 48));
            buffer.writeByte(nextOffset(offset), (byte) (value >> 56));

        }
    }

    private class BytesWriter extends AbstractBytesWriter {


        public BytesWriter(@NotNull Bytes buffer) {
            super(buffer);
        }


        private long write(long offset, @NotNull Bytes bytes) {
            long result = offset + bytes.remaining();
            offset %= capacity();
            long len = bytes.remaining();
            long endOffSet = nextOffset(offset, len);

            if (endOffSet >= offset) {
                this.buffer.write(offset, bytes);
                return result;
            }

            long limit = bytes.limit();

            bytes.limit(capacity() - offset);
            this.buffer.write(offset, bytes);

            bytes.position(bytes.limit());
            bytes.limit(limit);

            this.buffer.write(0, bytes);

            return result;

        }


        private long write(long offset, long value) {

            long result = offset + 8;
            offset %= capacity();

            if (nextOffset(offset, 8) > offset)
                buffer.writeLong(offset, value);
            else if (isBytesBigEndian)
                putLongB(offset, value);
            else
                putLongL(offset, value);

            return result;
        }


        public long writeByte(long l, int i) {
            buffer.writeByte(l % capacity(), i);


            return l + 1;
        }


    }

    private class BytesReader extends AbstractBytesWriter {


        public BytesReader(@NotNull Bytes buffer) {
            super(buffer);
        }

        long position;


        private long read(@NotNull Bytes bytes, long offset) {
            offset %= capacity();
            long endOffSet = nextOffset(offset, bytes.remaining());

            if (endOffSet >= offset) {
                bytes.write(buffer, offset, bytes.remaining());
                bytes.flip();
                return endOffSet;
            }

            bytes.write(buffer, offset, capacity() - offset);
            bytes.write(buffer, 0, bytes.remaining());
            bytes.flip();

            return endOffSet;

        }


        private long makeLong(byte b7, byte b6, byte b5, byte b4,
                              byte b3, byte b2, byte b1, byte b0) {
            return ((((long) b7) << 56) |
                    (((long) b6 & 0xff) << 48) |
                    (((long) b5 & 0xff) << 40) |
                    (((long) b4 & 0xff) << 32) |
                    (((long) b3 & 0xff) << 24) |
                    (((long) b2 & 0xff) << 16) |
                    (((long) b1 & 0xff) << 8) |
                    (((long) b0 & 0xff)));
        }

        long readLong(long offset) {

            offset %= capacity();
            if (nextOffset(offset, 8) > offset)
                return buffer.readLong(offset);

            return isBytesBigEndian ? makeLong(buffer.readByte(offset),
                    buffer.readByte(offset = nextOffset(offset)),
                    buffer.readByte(offset = nextOffset(offset)),
                    buffer.readByte(offset = nextOffset(offset)),
                    buffer.readByte(offset = nextOffset(offset)),
                    buffer.readByte(offset = nextOffset(offset)),
                    buffer.readByte(offset = nextOffset(offset)),
                    buffer.readByte(nextOffset(offset)))

                    : makeLong(buffer.readByte(nextOffset(offset, 7L)),
                    buffer.readByte(nextOffset(offset, 6L)),
                    buffer.readByte(nextOffset(offset, 5L)),
                    buffer.readByte(nextOffset(offset, 4L)),
                    buffer.readByte(nextOffset(offset, 3L)),
                    buffer.readByte(nextOffset(offset, 2L)),
                    buffer.readByte(nextOffset(offset)),
                    buffer.readByte(offset));
        }


        public byte readByte(long l) {
            return buffer.readByte(l % capacity());
        }
    }
}
