/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.queue.impl;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.MappedBytes;
import net.openhft.chronicle.bytes.ReadBytesMarshallable;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.annotation.ForceInline;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.ringbuffer.BytesRingBuffer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueStore;
import net.openhft.chronicle.threads.HandlerPriority;
import net.openhft.chronicle.threads.api.EventHandler;
import net.openhft.chronicle.threads.api.EventLoop;
import net.openhft.chronicle.threads.api.InvalidEventHandlerException;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.function.BiConsumer;

import static net.openhft.chronicle.bytes.NativeBytesStore.nativeStoreWithFixedCapacity;
import static net.openhft.chronicle.queue.ChronicleQueue.toCycle;
import static net.openhft.chronicle.queue.ChronicleQueue.toSequenceNumber;
import static net.openhft.chronicle.wire.Wires.toIntU30;

public class Excerpts {


    private static final Logger LOG = LoggerFactory.getLogger(Excerpts.class);


    // *************************************************************************
    //
    // APPENDERS
    //
    // *************************************************************************

    public static abstract class DefaultAppender<T extends ChronicleQueue> implements ExcerptAppender {
        @NotNull
        final T queue;

        public DefaultAppender(@NotNull T queue) {
            this.queue = queue;
        }

        @Override
        public long writeDocument(@NotNull WriteMarshallable writer) throws IOException {
            throw new UnsupportedOperationException();
        }


        @Override
        public long writeBytes(@NotNull Bytes<?> bytes) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public long index() {
            throw new UnsupportedOperationException();
        }


        public abstract long cycle();

        @NotNull
        @Override
        public ChronicleQueue queue() {
            return this.queue;
        }
    }


    /**
     * Unlike the other appenders the write methods are not able to return the index that he exceprt
     * was written to, as the write is deferred  using a ring buffer , and later written using a
     * background thread
     *
     * @author Rob Austin.
     */
    public static class BufferedAppender implements ExcerptAppender {

        @NotNull
        private final BytesRingBuffer ringBuffer;
        @NotNull
        private final StoreAppender underlyingAppender;
        private final Wire tempWire;
        @NotNull
        private final EventLoop eventLoop;

        public BufferedAppender(@NotNull final EventLoop eventLoop,
                                @NotNull final StoreAppender underlyingAppender,
                                final long ringBufferCapacity) {
            this.eventLoop = eventLoop;
            ringBuffer = BytesRingBuffer.newInstance(nativeStoreWithFixedCapacity(
                    ringBufferCapacity));

            this.underlyingAppender = underlyingAppender;
            this.tempWire = underlyingAppender.queue().wireType().apply(Bytes.elasticByteBuffer());

            final EventHandler handler = () -> {

                final Wire wire = underlyingAppender.wire();
                final Bytes<?> bytes = wire.bytes();
                final long start = bytes.writePosition();

                bytes.writeInt(Wires.NOT_READY);

                try {
                    if (!ringBuffer.read(bytes)) {
                        bytes.writeSkip(-4);
                        bytes.writeInt(bytes.writePosition(), 0);
                        return false;
                    }

                    final long len = bytes.writePosition() - start - 4;

                    // no data was read from the ring buffer, so we wont write any docuement to
                    // the appender
                    if (len == 0) {
                        bytes.writeSkip(-4);
                        bytes.writeInt(bytes.writePosition(), 0);
                        return false;
                    }

                    bytes.writeInt(start, toIntU30(len, "Document length %,d " +
                            "out of 30-bit int range."));

                    underlyingAppender.index++;
                    underlyingAppender.store().writePosition(wire.bytes().writePosition());
                    underlyingAppender.store().storeIndexLocation(wire, start,
                            underlyingAppender.index);

                    return true;

                } catch (Throwable t) {
                    throw Jvm.rethrow(t);
                }

            };

            eventLoop.addHandler(handler);

            eventLoop.addHandler(new EventHandler() {
                @Override
                public boolean action() throws InvalidEventHandlerException {
                    long writeBytesRemaining = ringBuffer.minNumberOfWriteBytesRemaining();
                    if (writeBytesRemaining == Long.MAX_VALUE)
                        return false;
                    long readCount = ringBuffer.numberOfReadsSinceLastCall();
                    long writeCount = ringBuffer.numberOfWritesSinceLastCall();
                    long maxCopyTime = ringBuffer.maxCopyTimeSinceLastCall();


                    // the capacity1 is slightly less than the memory allocated to the ring
                    // as the ring buffer itself uses some memory for the header
                    final long capacity1 = ringBuffer.capacity();

                    final double percentage = ((double) writeBytesRemaining / (double)
                            capacity1) * 100;
                    if (percentage < 0.5)
                        LOG.info("ring buffer=" + (capacity1 - writeBytesRemaining) / 1024 +
                                "KB/" + capacity1 / 1024 + "KB [" + (int) percentage + "% Free], writes=" + writeCount + ", reads=" + readCount + ", maxCopyTime=" + maxCopyTime / 1000 + " us");

                    return true;
                }

                @NotNull
                @Override
                public HandlerPriority priority() {
                    return HandlerPriority.MONITOR;
                }
            });

            eventLoop.start();

        }

        @NotNull
        public BytesRingBuffer ringBuffer() {
            return ringBuffer;
        }

        /**
         * for the best performance use net.openhft.chronicle.queue.impl.Excerpts.BufferedAppender#writeBytes(net.openhft.chronicle.bytes.Bytes)
         *
         * @param writer to write to excerpt.
         * @return always returns -1 when using the buffered appender
         * @throws IOException
         */
        @Override
        public long writeDocument(@NotNull WriteMarshallable writer) throws IOException {
            final Bytes<?> bytes = tempWire.bytes();
            bytes.clear();
            writer.writeMarshallable(tempWire);
            return writeBytes(bytes);
        }

        /**
         * for the best performacne use net.openhft.chronicle.queue.impl.Excerpts.BufferedAppender#writeBytes(net.openhft.chronicle.bytes.Bytes)
         *
         * @param marshallable to write to excerpt.
         * @return always returns -1 when using the buffered appender
         * @throws IOException
         */
        @Override
        public long writeBytes(@NotNull WriteBytesMarshallable marshallable) throws IOException {
            final Bytes<?> bytes = tempWire.bytes();
            bytes.clear();
            marshallable.writeMarshallable(bytes);
            return writeBytes(bytes);
        }

        /**
         * for the best performance call this method, rather than net.openhft.chronicle.queue.impl.Excerpts.BufferedAppender#writeBytes(net.openhft.chronicle.bytes.WriteBytesMarshallable)
         * or net.openhft.chronicle.queue.impl.Excerpts.BufferedAppender#writeDocument(net.openhft.chronicle.wire.WriteMarshallable)
         *
         * @param bytes to write to excerpt.
         * @return always returns -1 when using the buffered appender
         * @throws IOException
         */
        @Override
        public long writeBytes(@NotNull Bytes<?> bytes) throws IOException {
            try {
                while (!ringBuffer.offer(bytes))
                    Thread.yield();
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

        @NotNull
        @Override
        public ChronicleQueue queue() {
            return underlyingAppender.queue();
        }


        @Override
        public void prefetch() {
        }

    }


    /**
     * StoreAppender
     */
    public static class StoreAppender extends DefaultAppender<AbstractChronicleQueue> {
        long index = -1;
        private Wire wire;
        private long cycle;
        private WireStore store;
        private long nextPrefetch = OS.pageSize();

        public StoreAppender(@NotNull AbstractChronicleQueue queue) {

            super(queue);

            final long lastIndex = super.queue.lastIndex();
            this.cycle = (lastIndex == -1) ? queue.cycle() : toCycle(lastIndex);

            if (this.cycle < 0)
                throw new IllegalArgumentException("You can not have a cycle that starts " +
                        "before Epoch. cycle=" + cycle);

            this.store = queue.storeForCycle(this.cycle, queue.epoch());
            this.index = this.store.sequenceNumber();

            final MappedBytes mappedBytes = store.mappedBytes();
            if (LOG.isDebugEnabled())
                LOG.debug("appender file=" + mappedBytes.mappedFile().file().getAbsolutePath());

            wire = this.queue().wireType().apply(mappedBytes);
        }

        public long writeDocument(@NotNull WriteMarshallable writer) throws IOException {
            final WireStore store = store();

            long position;

            do {

                position = WireInternal.writeDataOrAdvanceIfNotEmpty(wire, false, writer);

                // this will be called if currently being modified with unknown length
                if (position == 0)
                    continue;

                this.index++;

            } while (position <= 0);

            store.writePosition(wire.bytes().writePosition());
            store.storeIndexLocation(wire, position, index);
            return ChronicleQueue.index(store.cycle(), index);
        }

        @Override
        public long writeBytes(@NotNull WriteBytesMarshallable marshallable) throws IOException {
            return writeDocument(wire1 -> marshallable.writeMarshallable(wire1.bytes()));
        }

        @Override
        public long writeBytes(@NotNull Bytes bytes) throws IOException {
            return writeDocument(wire1 -> wire1.bytes().write(bytes));
        }

        @Override
        public long index() {
            if (this.index == -1) {
                throw new IllegalStateException();
            }

            return ChronicleQueue.index(cycle(), index);
        }

        @Override
        public long cycle() {
            return this.store.cycle();
        }


        Wire wire() {
            return wire;
        }

        @ForceInline
        private WireStore store() {
            if (this.cycle != queue.cycle()) {
                long nextCycle = queue.cycle();
                if (this.store != null) {
                    while (!this.store.appendRollMeta(wire, nextCycle)) {
                        Thread.yield();
                    }
                    this.queue.release(this.store);
                }

                this.cycle = nextCycle;
                this.store = queue.storeForCycle(this.cycle, queue.epoch());
                this.wire = this.queue().wireType().apply(store.mappedBytes());
            }

            return this.store;
        }

        @Override
        public void prefetch() {
            long position = wire.bytes().writePosition();
            if (position < nextPrefetch)
                return;
            long prefetch = OS.mapAlign(position);
//            System.out.println(Thread.currentThread().getName()+" prefetch "+prefetch);
            // touch the page without modifying it.
            wire.bytes().compareAndSwapInt(prefetch, ~0, ~0);
            nextPrefetch = prefetch + OS.pageSize();
        }
    }

// *************************************************************************
//
// TAILERS
//
// *************************************************************************

    /**
     * Tailer
     */
    public static class StoreTailer implements ExcerptTailer {
        @NotNull
        private final AbstractChronicleQueue queue;
        private Wire wire;

        private long cycle;

        private long index;
        private WireStore store;
        private long nextPrefetch = OS.pageSize();

        public StoreTailer(@NotNull AbstractChronicleQueue queue) throws IOException {
            this.queue = queue;
            this.cycle = -1;

            toStart();
        }

        @Override
        public boolean readDocument(@NotNull ReadMarshallable marshaller) throws IOException {
            return readAtIndex(marshaller, ReadMarshallable::readMarshallable);
        }

        @Override
        public boolean readBytes(@NotNull Bytes using) throws IOException {
            return readAtIndex(using, (t, w) -> t.write(w.bytes()));
        }

        @Override
        public boolean readBytes(@NotNull ReadBytesMarshallable using) throws IOException {
            return readAtIndex(using, (t, w) -> t.readMarshallable(w.bytes()));
        }

        private <T> boolean readAtIndex(T t, @NotNull BiConsumer<T, Wire> c) throws IOException {

            final long readPosition = wire.bytes().readPosition();
            final long readLimit = wire.bytes().readLimit();
            final long cycle = this.cycle;
            final long index = this.index;

            if (this.store == null) {
                final long firstIndex = queue.firstIndex();
                if (index == -1) {
                    return false;
                }
                moveToIndex(firstIndex);
            }

            final boolean success = readAt(t, c);
            if (success) {
                this.index = ChronicleQueue.index(cycle, toSequenceNumber(index) + 1);
                return true;
            }

            // roll detected, move to next cycle;
            cycle(cycle);
            wire.bytes().readLimit(readLimit);
            wire.bytes().readPosition(readPosition);
            return false;
        }

        private <T> boolean readAt(T t, @NotNull BiConsumer<T, Wire> c) throws IOException {

            long roll;
            for (; ; ) {
                roll = Long.MIN_VALUE;
                wire.bytes().readLimit(wire.bytes().capacity());

                while (wire.bytes().readLong(wire.bytes().readPosition()) != 0) {

                    try (@NotNull final DocumentContext documentContext = wire.readingDocument()) {

                        if (!documentContext.isPresent())
                            return false;

                        if (documentContext.isData()) {
                            c.accept(t, wire);
                            return true;
                        }

                        // In case of meta data, if we are found the "roll" meta, we returns
                        // the next cycle (negative)
                        final StringBuilder sb = Wires.acquireStringBuilder();

                        final ValueIn vi = wire.readEventName(sb);
                        if ("roll".contentEquals(sb)) {
                            roll = vi.int32();
                            break;
                        }

                    }

                }

                if (roll == Long.MIN_VALUE)
                    return false;

                // roll to the next file
                cycle(roll);
                if (store == null)
                    return false;
            }


        }

        /**
         * @return provides an index that includes the cycle number
         */
        @Override
        public long index() {

            if (this.store == null)
                throw new IllegalArgumentException("This tailer is not bound to any cycle");

            return ChronicleQueue.index(this.cycle, this.index);
        }


        @Override
        public boolean moveToIndex(long index) throws IOException {

            if (LOG.isDebugEnabled()) {
                LOG.debug(SingleChronicleQueueStore.IndexOffset.toBinaryString(index));
                LOG.debug(SingleChronicleQueueStore.IndexOffset.toScale());
            }


            final long expectedCycle = toCycle(index);
            if (expectedCycle != cycle)
                // moves to the expected cycle
                cycle(expectedCycle);

            cycle = expectedCycle;

            final Bytes<?> bytes = wire.bytes();

            final long sequenceNumber = toSequenceNumber(index);
            if (sequenceNumber == -1) {
                bytes.readPosition(0);
                this.index = ChronicleQueue.index(cycle, sequenceNumber);
                return true;
            }

            final long position = this.store.moveToIndex(wire, ChronicleQueue.toSequenceNumber(index));

            if (position == -1)
                return false;

            bytes.readPosition(position);
            bytes.readLimit(bytes.realCapacity());

            this.index = ChronicleQueue.index(cycle, sequenceNumber - 1);
            return true;
        }


        @NotNull
        @Override
        public ExcerptTailer toStart() throws IOException {

            final long index = queue.firstIndex();
            if (ChronicleQueue.toSequenceNumber(index) == -1) {
                cycle(toCycle(index));
                this.wire.bytes().readPosition(0);
                return this;
            }

            LOG.info("index=> index=" + toSequenceNumber(index) + ",cycle=" +
                    toCycle(index));


            if (!moveToIndex(index))
                throw new IllegalStateException("unable to move to the start, cycle=" + cycle);

            return this;
        }

        @NotNull
        @Override
        public ExcerptTailer toEnd() throws IOException {

            if (!moveToIndex(queue.lastIndex()))
                throw new IllegalStateException("unable to move to the start");

            return this;
        }

        @NotNull
        @Override
        public ChronicleQueue queue() {
            return this.queue;
        }

        @NotNull
        private StoreTailer cycle(long cycle) throws IOException {
            if (this.cycle != cycle) {
                if (null != this.store) {
                    this.queue.release(this.store);
                }
                this.cycle = cycle;
                //  this.index = -1;
                this.store = this.queue.storeForCycle(cycle, queue.epoch());

                wire = queue.wireType().apply(store.mappedBytes());
                moveToIndex(ChronicleQueue.index(cycle, -1));
                if (LOG.isDebugEnabled())
                    LOG.debug("tailer=" + ((MappedBytes) wire.bytes()).mappedFile().file().getAbsolutePath());

            }

            return this;
        }


        @Override
        public void prefetch() {
            long position = wire.bytes().readPosition();
            if (position < nextPrefetch)
                return;
            long prefetch = OS.mapAlign(position) + OS.pageSize();
            // touch the page without modifying it.
            wire.bytes().compareAndSwapInt(prefetch, ~0, ~0);
            nextPrefetch = prefetch + OS.pageSize();
        }

    }
}

