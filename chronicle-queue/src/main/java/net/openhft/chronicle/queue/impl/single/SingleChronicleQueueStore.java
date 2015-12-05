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
package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.bytes.*;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.ReferenceCounter;
import net.openhft.chronicle.core.annotation.ForceInline;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.core.values.LongArrayValues;
import net.openhft.chronicle.core.values.LongValue;
import net.openhft.chronicle.queue.ChronicleQueueBuilder;
import net.openhft.chronicle.queue.RollCycle;
import net.openhft.chronicle.queue.impl.WireConstants;
import net.openhft.chronicle.queue.impl.WireStore;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.time.ZoneId;
import java.util.function.Function;

import static java.lang.ThreadLocal.withInitial;
import static net.openhft.chronicle.queue.impl.WireConstants.SPB_DATA_HEADER_SIZE;
import static net.openhft.chronicle.queue.impl.single.SingleChronicleQueueStore.IndexOffset.toAddress0;
import static net.openhft.chronicle.queue.impl.single.SingleChronicleQueueStore.IndexOffset.toAddress1;
import static net.openhft.chronicle.wire.Wires.NOT_INITIALIZED;
import static net.openhft.chronicle.wire.Wires.NOT_READY;


class SingleChronicleQueueStore implements WireStore {

    static {
        ClassAliasPool.CLASS_ALIASES.addAlias(Bounds.class, "Bounds");
        ClassAliasPool.CLASS_ALIASES.addAlias(Indexing.class, "Indexing");
        ClassAliasPool.CLASS_ALIASES.addAlias(Roll.class, "Roll");
    }

    private final WireType wireType;

    private MappedFile mappedFile;


    enum MetaDataField implements WireKey {
        bounds,
        indexing,
        roll,
        mappedFile
    }


    private Closeable resourceCleaner;
    private SingleChronicleQueueBuilder builder;

    private final ReferenceCounter refCount = ReferenceCounter.onReleased(this::performRelease);


    Bounds bounds = new Bounds();
    private Indexing indexing;
    private final Roll roll;

    /**
     * Default constructor needed for self boot-strapping
     */
    SingleChronicleQueueStore() {
        this.wireType = WireType.BINARY;
        this.roll = new Roll(null);
    }

    SingleChronicleQueueStore(@Nullable RollCycle rollCycle,
                              final WireType wireType, MappedFile mappedFile) {

        this.roll = new Roll(rollCycle);
        this.resourceCleaner = null;
        this.builder = null;
        this.wireType = wireType;
        this.mappedFile = mappedFile;
        init();
    }

    void mappedFile(@NotNull MappedFile mappedFile) {
        this.mappedFile = mappedFile;
    }

    private void init() {
        indexing = new Indexing(wireType, mappedFile);
    }


    @Override
    public long readPosition() {
        return this.bounds.getReadPosition();
    }

    @Override
    public long writePosition() {
        return this.bounds.getWritePosition();
    }


    @Override
    public long cycle() {
        return this.roll.getCycle();
    }

    @Override
    public long lastIndex() {
        return this.indexing.getLastIndex();
    }

    @Override
    public long append(@NotNull MappedBytes context, @NotNull final WriteMarshallable marshallable) throws IOException {
        return write(context, Wires.UNKNOWN_LENGTH, this::writeWireMarshallable, marshallable);
    }

    @Override
    public long append(@NotNull MappedBytes context, @NotNull final WriteBytesMarshallable marshallable) throws IOException {
        return write(context, Wires.UNKNOWN_LENGTH, this::writeBytesMarshallable, marshallable);
    }

    @Override
    public long append(@NotNull MappedBytes context, @NotNull final Bytes bytes) throws IOException {
        return write(context, toIntU30(bytes.length()), this::writeBytes, bytes);
    }

    @Override
    public long read(@NotNull MappedBytes context, @NotNull ReadMarshallable reader) throws IOException {
        return read(context, this::readWireMarshallable, reader);
    }

    @Override
    public long read(@NotNull MappedBytes context, @NotNull ReadBytesMarshallable reader) throws IOException {
        return read(context, this::readBytesMarshallable, reader);
    }

    @Override
    public boolean appendRollMeta(@NotNull MappedBytes context, long cycle) throws IOException {
        if (roll.casNextRollCycle(cycle)) {
            final WriteMarshallable marshallable = x -> x.write(MetaDataField.roll).int32(cycle);

            write(
                    context,
                    Wires.UNKNOWN_LENGTH,
                    (MappedBytes ctx, long position, int size, WriteMarshallable w) -> {
                        // todo improve this line
                        Wires.writeMeta(wireType.apply(context), w);
                        roll.setNextCycleMetaPosition(position);
                        return WireConstants.NO_INDEX;
                    },
                    marshallable
            );

            return true;
        }

        return false;
    }

    @Override
    public boolean moveToIndex(@NotNull MappedBytes context, long index) {
        return indexing.moveToIndex(context, index);
    }

    @Override
    public void reserve() throws IllegalStateException {
        this.refCount.reserve();
    }

    @Override
    public void release() throws IllegalStateException {
        this.refCount.release();
    }

    @Override
    public long refCount() {
        return this.refCount.get();
    }

    // *************************************************************************
    // BOOTSTRAP
    // *************************************************************************

    @Override
    public void install(
            @NotNull MappedFile mappedFile,
            long length,
            boolean created,
            long cycle,
            ChronicleQueueBuilder builder,
            @NotNull Function<Bytes, Wire> wireSupplier,
            @Nullable Closeable closeable) throws IOException {

        this.builder = (SingleChronicleQueueBuilder) builder;

        if (created) {
            this.bounds.setWritePosition(length);
            this.bounds.setReadPosition(length);
            this.roll.setCycle(cycle);
        }
    }

    @Override
    public MappedFile mappedFile() {
        return mappedFile;
    }


    // *************************************************************************
    // Utilities
    // *************************************************************************

    private synchronized void performRelease() {
        //TODO: implement
        try {
            if (this.resourceCleaner != null) {
                this.resourceCleaner.close();
            }
        } catch (IOException e) {
            //TODO
        }
    }

    private int toIntU30(long len) {
        return Wires.toIntU30(len, "Document length %,d out of 30-bit int range.");
    }

    // *************************************************************************
    // Utilities :: Read
    // *************************************************************************

    @FunctionalInterface
    private interface Reader<T> {
        long read(@NotNull MappedBytes context, int len, @NotNull T reader) throws IOException;
    }

    private long readWireMarshallable(
            @NotNull MappedBytes context,
            int len,
            @NotNull ReadMarshallable marshaller) {

        context.readSkip(SPB_DATA_HEADER_SIZE);
        return readWire(wireType.apply(context), len, marshaller);
    }

    private long readBytesMarshallable(
            @NotNull MappedBytes context,
            int len,
            @NotNull ReadBytesMarshallable marshaller) {

        context.readSkip(SPB_DATA_HEADER_SIZE);
        final long readp = context.readPosition();
        final long readl = context.readLimit();

        marshaller.readMarshallable(context);
        context.readPosition(readp + len);
        context.readLimit(readl);

        return readp + len;
    }

    private <T> long read(
            @NotNull MappedBytes context,
            @NotNull Reader<T> reader,
            @NotNull T marshaller) throws IOException {

        long position = context.readPosition();

        final int spbHeader = context.readVolatileInt(position);
        if (!Wires.isNotInitialized(spbHeader) && Wires.isReady(spbHeader)) {
            int len = Wires.lengthOf(spbHeader);
            if (Wires.isData(spbHeader)) {
                return reader.read(context, len, marshaller);
            } else {
                // In case of meta data, if we are found the "roll" meta, we returns
                // the next cycle (negative)
                final StringBuilder sb = Wires.acquireStringBuilder();

                // todo improve this line
                final ValueIn vi = wireType.apply(context).read(sb);

                if ("index".contentEquals(sb)) {
                    return read(context, reader, marshaller);
                } else if ("roll".contentEquals(sb)) {
                    return -vi.int32();
                } else {
                    context.readLimit(context.capacity());
                    context.readPosition(position + len + SPB_DATA_HEADER_SIZE);
                    return read(context, reader, marshaller);
                }
            }
        }

        return WireConstants.NO_DATA;
    }

    //TODO : maybe move to wire
    @ForceInline
    private long readWire(@NotNull WireIn wireIn, long size, @NotNull ReadMarshallable dataConsumer) {
        final Bytes<?> bytes = wireIn.bytes();
        final long limit0 = bytes.readLimit();
        final long limit = bytes.readPosition() + size;
        try {
            bytes.readLimit(limit);
            dataConsumer.readMarshallable(wireIn);
        } finally {
            bytes.readLimit(limit0);
            bytes.readPosition(limit);
        }

        return bytes.readPosition();
    }

    // *************************************************************************
    // Utilities :: Write
    // *************************************************************************

    @FunctionalInterface
    private interface Writer<T> {
        long write(@NotNull MappedBytes context, long position, int size, @NotNull T writer) throws IOException;
    }

    private long writeWireMarshallable(
            @NotNull MappedBytes context,
            long position,
            int size,
            @NotNull final WriteMarshallable marshallable) throws IOException {

        // todo improve this line
        bounds.setWritePositionIfGreater(Wires.writeData(wireType.apply(context), marshallable));

        final long index = indexing.incrementLastIndex();
        indexing.storeIndexLocation(context, position, index);
        return index;
    }

    private long writeBytesMarshallable(
            @NotNull MappedBytes context,
            long position,
            int size,
            @NotNull final WriteBytesMarshallable marshallable) throws IOException {

        context.writeSkip(SPB_DATA_HEADER_SIZE);

        marshallable.writeMarshallable(context);
        context.compareAndSwapInt(
                position,
                Wires.NOT_READY,
                toIntU30(context.writePosition() - position - SPB_DATA_HEADER_SIZE)
        );

        bounds.setWritePositionIfGreater(position);
        final long index = indexing.incrementLastIndex();
        indexing.storeIndexLocation(context, position, index);
        return index;
    }

    private long writeBytes(
            @NotNull MappedBytes context,
            long position,
            int size,
            @NotNull final Bytes bytes) throws IOException {

        context.writeSkip(4);
        context.write(bytes);
        context.compareAndSwapInt(position, size | Wires.NOT_READY, size);

        final long index = indexing.incrementLastIndex();
        indexing.storeIndexLocation(context, position, index);
        return index;
    }


    private long writeIndexBytes(
            @NotNull MappedBytes context,
            long position,
            int size,
            @NotNull final Bytes bytes) throws IOException {

        context.writeSkip(4);
        context.write(bytes);
        context.compareAndSwapInt(position, size | Wires.NOT_READY, size);
        return 0;
    }

    private <T> long write(
            @NotNull MappedBytes context,
            int size,
            @NotNull Writer<T> writer,
            @NotNull T marshaller) throws IOException {

        final long end = System.currentTimeMillis() + builder.appendTimeout();
        long position = writePosition();

        for (; ; ) {

            context.writeLimit(context.capacity());
            context.writePosition(position);

            if (context.compareAndSwapInt(position, Wires.NOT_INITIALIZED, Wires.NOT_READY | size)) {
                return writer.write(context, position, size, marshaller);
            } else {
                int spbHeader = context.readInt(position);
                if (Wires.isKnownLength(spbHeader)) {
                    position += Wires.lengthOf(spbHeader) + SPB_DATA_HEADER_SIZE;
                } else {
                    // TODO: wait strategy
                    if (System.currentTimeMillis() > end) {
                        throw new AssertionError("Timeout waiting to append");
                    }

                    Jvm.pause(1);
                }
            }
        }
    }

    // *************************************************************************
    // Marshallable
    // *************************************************************************


    @Override
    public void writeMarshallable(@NotNull WireOut wire) {
        ;
        wire.write(MetaDataField.bounds).marshallable(this.bounds)
                .write(MetaDataField.indexing).object(this.indexing)
                .write(MetaDataField.roll).object(this.roll)
                .write(MetaDataField.mappedFile).object(new MarshallableMappedFile(this
                .mappedFile));
    }


    @Override
    public void readMarshallable(@NotNull WireIn wire) throws IORuntimeException {
        wire.read(MetaDataField.bounds).marshallable(this.bounds)
                .read(MetaDataField.indexing).object(Indexing.class, this, (t, v) -> t.indexing = v)
                .read(MetaDataField.roll).marshallable(this.roll)
                .read(MetaDataField.mappedFile).object(MarshallableMappedFile.class,
                this, (t, v) -> {
                    t.mappedFile = v.mf();
                    t.init();
                });

        init();


    }


// *************************************************************************
//
// *************************************************************************

    enum BoundsField implements WireKey {
        writePosition,
        readPosition,
    }

    class Bounds implements Marshallable {
        private LongValue writePosition;
        private LongValue readPosition;

        Bounds() {
            this.writePosition = null;
            this.readPosition = null;
        }

        @Override
        public void writeMarshallable(@NotNull WireOut wire) {
            wire.write(BoundsField.writePosition).int64forBinding(
                    WireConstants.HEADER_OFFSET, writePosition = wire.newLongReference())
                    .write(BoundsField.readPosition).int64forBinding(
                    WireConstants.HEADER_OFFSET, readPosition = wire.newLongReference());
        }

        @Override
        public void readMarshallable(@NotNull WireIn wire) {
            wire.read(BoundsField.writePosition).int64(
                    this.writePosition, this, (o, i) -> o.writePosition = i)
                    .read(BoundsField.readPosition).int64(
                    this.readPosition, this, (o, i) -> o.readPosition = i);
        }

        public long getReadPosition() {
            return this.readPosition.getVolatileValue();
        }

        public void setReadPosition(long position) {
            this.readPosition.setOrderedValue(position);
        }

        public long getWritePosition() {
            return this.writePosition.getVolatileValue();
        }

        public void setWritePosition(long position) {
            this.writePosition.setOrderedValue(position);
        }

        public void setWritePositionIfGreater(long writePosition) {
            for (; ; ) {
                long wp = writePosition();
                if (writePosition > wp) {
                    if (this.writePosition.compareAndSwapValue(wp, writePosition)) {
                        return;
                    }
                } else {
                    break;
                }
            }
        }
    }

// *************************************************************************
//
// *************************************************************************

    enum IndexingFields implements WireKey {
        indexCount, indexSpacing, index2Index, lastIndex
    }

    public static final long NUMBER_OF_ENTRIES_IN_EACH_INDEX = 1 << 17;

    class Indexing implements Marshallable {
        private final WireType wireType;
        private final MappedBytes indexContext;
        private int indexCount;
        private int indexSpacing;
        private LongValue index2Index;
        private LongValue lastIndex;
        private final Wire templateIndex;
        private ThreadLocal<LongArrayValues> longArray;

        Indexing(@NotNull WireType wireType, final MappedFile mappedFile) {
            this.indexCount = 128 << 10;
            this.indexSpacing = 64;
            this.index2Index = null;
            this.lastIndex = null;

            final Bytes b = Bytes.elasticByteBuffer();

            templateIndex = wireType.apply(b);
            templateIndex.writeDocument(true, w -> w.write(() -> "index")
                    .int64array(NUMBER_OF_ENTRIES_IN_EACH_INDEX));

            this.wireType = wireType;
            this.longArray = withInitial(wireType.newLongArrayReference());
            this.indexContext = new MappedBytes(mappedFile);
        }

        @Override
        public void writeMarshallable(@NotNull WireOut wire) {
            wire.write(IndexingFields.indexCount).int32(indexCount)
                    .write(IndexingFields.indexSpacing).int32(indexSpacing)
                    .write(IndexingFields.index2Index).int64forBinding(0L, index2Index = wire.newLongReference())
                    .write(IndexingFields.lastIndex).int64forBinding(-1L, lastIndex = wire.newLongReference());
        }

        @Override
        public void readMarshallable(@NotNull WireIn wire) {
            wire.read(IndexingFields.indexCount).int32(this, (o, i) -> o.indexCount = i)
                    .read(IndexingFields.indexSpacing).int32(this, (o, i) -> o.indexSpacing = i)
                    .read(IndexingFields.index2Index).int64(this.index2Index, this, (o, i) -> o.index2Index = i)
                    .read(IndexingFields.lastIndex).int64(this.lastIndex, this, (o, i) -> o.lastIndex = i);
        }

        public long incrementLastIndex() {
            if (lastIndex == null)
                return 0;
            return this.lastIndex.addAtomicValue(1);
        }

        public long getLastIndex() {
            if (lastIndex == null)
                return 0;
            return this.lastIndex.getVolatileValue();
        }


        /**
         * atomically gets or creates the address of the first index the index is create and another
         * except into the queue, however this except is treated as meta data and does not increment
         * the last index, in otherword it is not possible to access this except by calling index(),
         * it effectively invisible to the end-user
         *
         * @param writeContext used to write and index if it does not exist
         * @return the position of the index
         */
        long indexToIndex(@Nullable final MappedBytes writeContext) {
            for (; ; ) {
                long index2Index = this.index2Index.getVolatileValue();

                if (index2Index == NOT_READY)
                    continue;

                if (index2Index != NOT_INITIALIZED)
                    return index2Index;

                if (!this.index2Index.compareAndSwapValue(NOT_INITIALIZED, NOT_READY))
                    continue;

                if (writeContext == null)
                    return -1;

                final long index = newIndex(writeContext);
                this.index2Index.setOrderedValue(index);
                return index;
            }
        }


        /**
         * records the the location of the index, only every 64th address is written to the index
         * file, the first index is stored at {@code index2index}
         *
         * @param context the context that we are referring to
         * @param address the address of the Excerpts which we are going to record
         * @param index   the index of the Excerpts which we are going to record
         */
        public void storeIndexLocation(MappedBytes context,
                                       final long address,
                                       final long index) throws IOException {

            if (index % 64 != 0 || index == 0)
                return;

            final LongArrayValues array = this.longArray.get();
            final long indexToIndex0 = indexToIndex(context);

            final MappedBytes indexBytes = indexContext;
            indexBytes.readLimit(indexBytes.capacity());
            final Bytes bytes0 = indexBytes.readPosition(indexToIndex0);
            final Wire w = wireType.apply(bytes0);

            w.readDocument(d -> {

                final LongArrayValues primaryIndex = array(w, array);
                final long primaryOffset = toAddress0(index);
                long secondaryAddress = primaryIndex.getValueAt(primaryOffset);

                if (secondaryAddress == Wires.NOT_INITIALIZED) {
                    secondaryAddress = newIndex(context);
                    primaryIndex.setValueAt(primaryOffset, secondaryAddress);
                }

                indexBytes.readLimit(indexBytes.capacity());
                final Bytes bytes = indexBytes.readPosition(secondaryAddress);
                wireType.apply(bytes).readDocument(document -> {
                    final LongArrayValues array1 = array(document, array);
                    array1.setValueAt(toAddress1(index), address);
                }, null);

            }, null);

        }

        private LongArrayValues array(WireIn w, LongArrayValues using) {
            final ValueIn read = w.read(() -> "index");

            read.int64array(using, this, (o1, o2) -> {
            });
            return using;
        }


        /**
         * Creates a new Excerpt containing and index which will be 1L << 17L bytes long, This
         * method is used for creating both the primary and secondary indexes. Chronicle Queue uses
         * a root primary index ( each entry in the primary index points to a unique a secondary
         * index. The secondary index only records the address of every 64th except, the except are
         * linearly scanned from there on.
         *
         * @param writeContext
         * @return the address of the Excerpt containing the usable index, just after the header
         */
        long newIndex(MappedBytes writeContext) {

            try {
                final long start = writeContext.writePosition() + SPB_DATA_HEADER_SIZE;
                final Bytes<?> bytes = templateIndex.bytes();
                write(writeContext, toIntU30((long) bytes.length()) | Wires.META_DATA, this::writeIndexBytes, bytes);
                return start;
            } catch (Throwable e) {
                throw Jvm.rethrow(e);
            }

        }

        private long writeIndexBytes(
                @NotNull MappedBytes context,
                long position,
                int size,
                @NotNull final Bytes bytes) throws IOException {

            context.writeSkip(4);
            context.write(bytes);
            context.compareAndSwapInt(position, size | Wires.NOT_READY, size);

            final long index = indexing.incrementLastIndex();
            indexing.storeIndexLocation(context, position, index);

            return index;
        }


        private LongArrayValues values;

        private long readIndexAt(long index2Index, long offset) throws IOException {

            final MappedBytes indexBytes = indexContext;

            final Bytes bytes0 = indexBytes
                    .readLimit(indexBytes.capacity())
                    .readPosition(index2Index);

            final Wire w = this.wireType.apply(bytes0);

            final long[] result = new long[1];

            w.readDocument(wireIn -> {
                try {
                    wireIn.read(() -> "index").int64array(values, this, (o, v) -> o.values = v);
                    final long index = values.getVolatileValueAt(offset);
                    result[0] = index;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, null);

            return result[0];
        }


        /**
         * The indexes are stored in many excerpts, so the index2index tells chronicle where ( in
         * other words the address of where ) the root first level targetIndex is stored. The
         * indexing works like a tree, but only 2 levels deep, the root of the tree is at
         * index2index ( this first level targetIndex is 1MB in size and there is only one of them,
         * it only holds the addresses of the second level indexes, there will be many second level
         * indexes ( created on demand ), each is about 1MB in size  (this second level targetIndex
         * only stores the position of every 64th excerpt), so from every 64th excerpt a linear scan
         * occurs. The indexes are only built when the indexer is run, this could be on a background
         * thread. Each targetIndex is created into chronicle as an excerpt.
         */
        public boolean moveToIndex(MappedBytes context, final long targetIndex) {


            long index2index = this.index2Index.getVolatileValue();


            try {

                long address1 = readIndexAt(index2index, toAddress0(targetIndex));
                long fromAddress;

                if (address1 != 0) {

                    final long offset = toAddress1(targetIndex);
                    fromAddress = readIndexAt(address1, offset);

                    if (fromAddress != 0) {
                        ///    context.readPosition(address2);
                        long startIndex = ((targetIndex / 64L)) * 64L;

                        if (targetIndex == startIndex) {
                            readPosition(context, fromAddress);
                            return true;
                        }

                        return linearScan(context, targetIndex, startIndex, fromAddress);

                    } else {

                        // scan back in secondary index till we find any result then we will
                        // linear scan from their
                        for (long startIndex = offset; startIndex >= 0; startIndex--) {
                            fromAddress = readIndexAt(address1, toAddress1(targetIndex));
                            if (fromAddress != 0) {
                                return linearScan(context, targetIndex, startIndex, fromAddress);
                            }
                        }

                        return linearScan(context, targetIndex, 0, fromAddress);
                    }

                } else {
                    return linearScan(context, targetIndex, 0, 0);
                }


            } catch (Exception e) {
                e.printStackTrace();
            }

            return false;
        }

        private void readPosition(MappedBytes context, long position) throws IOException {

            context.readLimit(context.capacity());
            context.readPosition(position);
        }

        /**
         * moves the context to the index of {@code toIndex} by doing a linear scans form a {@code
         * fromKnownIndex} at  {@code knownAddress}
         *
         * note meta data is skipped and does not count to the indexes
         *
         * @param context        if successful, moves the context to an address relating to the
         *                       index {@code toIndex }
         * @param toIndex        the index that we wish to move the context to
         * @param fromKnownIndex a know index ( used as a starting point )
         * @param knownAddress   a know address ( used as a starting point )
         * @return {@code true} if successful
         * @see net.openhft.chronicle.queue.impl.single.SingleChronicleQueueStore.Indexing#moveToIndex
         */
        private boolean linearScan(MappedBytes context, long toIndex, long fromKnownIndex, long knownAddress) {

            long position = knownAddress;
            try {
                for (long i = fromKnownIndex; i <= toIndex; ) {

                    readPosition(context, position);

                    final int spbHeader = context.readVolatileInt(position);
                    if (Wires.isReady(spbHeader)) {
                        if (Wires.isData(spbHeader)) {
                            if (toIndex == i) {
                                return true;
                            }

                            i++;
                        }

                        // todo
                        //  if (i % 64 == 0)
                        //    storeIndexLocation(context,context.readPosition(),i);

                        final int len = Wires.lengthOf(spbHeader);
                        context.readSkip(len + SPB_DATA_HEADER_SIZE);
                        position = context.readPosition();
                    } else {
                        return false;
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }

            return false;
        }


    }

    public enum IndexOffset {
        ;

        public static long toAddress0(long index) {

            long siftedIndex = index >> (17L + 6L);
            long mask = (1L << 17L) - 1L;
            long maskedShiftedIndex = mask & siftedIndex;

            // convert to an offset
            return maskedShiftedIndex * 8L;
        }

        public static long toAddress1(long index) {

            long siftedIndex = index >> (6L);
            long mask = (1L << 17L) - 1L;
            long maskedShiftedIndex = mask & siftedIndex;

            // convert to an offset
            return maskedShiftedIndex;// * 8L;
        }


        @NotNull
        public static String toBinaryString(long i) {

            StringBuilder sb = new StringBuilder();

            for (int n = 63; n >= 0; n--)
                sb.append(((i & (1L << n)) != 0 ? "1" : "0"));

            return sb.toString();
        }

        @NotNull
        public static String toScale() {

            StringBuilder units = new StringBuilder();
            StringBuilder tens = new StringBuilder();

            for (int n = 64; n >= 1; n--)
                units.append((0 == (n % 10)) ? "|" : n % 10);

            for (int n = 64; n >= 1; n--)
                tens.append((0 == (n % 10)) ? n / 10 : " ");

            return units.toString() + "\n" + tens.toString();
        }


    }

// *************************************************************************
//
// *************************************************************************

    enum RollFields implements WireKey {
        cycle, length, format, timeZone, nextCycle, nextCycleMetaPosition
    }

    class Roll implements Marshallable {
        private int length;
        private String format;
        private ZoneId zoneId;
        private LongValue cycle;
        private LongValue nextCycle;
        private LongValue nextCycleMetaPosition;

        Roll(RollCycle rollCycle) {
            this.length = rollCycle != null ? rollCycle.length() : -1;
            this.format = rollCycle != null ? rollCycle.format() : null;
            this.zoneId = rollCycle != null ? rollCycle.zone() : null;

            this.cycle = null;
            this.nextCycle = null;
            this.nextCycleMetaPosition = null;
        }

        @Override
        public void writeMarshallable(@NotNull WireOut wire) {
            wire.write(RollFields.cycle).int64forBinding(-1, cycle = wire.newLongReference())
                    .write(RollFields.length).int32(length)
                    .write(RollFields.format).text(format)
                    .write(RollFields.timeZone).text(zoneId.getId())
                    .write(RollFields.nextCycle).int64forBinding(-1, nextCycle = wire.newLongReference())
                    .write(RollFields.nextCycleMetaPosition).int64forBinding(-1, nextCycleMetaPosition = wire.newLongReference());
        }

        @Override
        public void readMarshallable(@NotNull WireIn wire) {
            wire.read(RollFields.cycle).int64(this.cycle, this, (o, i) -> o.cycle = i)
                    .read(RollFields.length).int32(this, (o, i) -> o.length = i)
                    .read(RollFields.format).text(this, (o, i) -> o.format = i)
                    .read(RollFields.timeZone).text(this, (o, i) -> o.zoneId = ZoneId.of(i))
                    .read(RollFields.nextCycle).int64(this.nextCycle, this, (o, i) -> o.nextCycle = i)
                    .read(RollFields.nextCycleMetaPosition).int64(this.nextCycleMetaPosition, this, (o, i) -> o.nextCycleMetaPosition = i);
        }

        public long getCycle() {
            return this.cycle.getVolatileValue();
        }

        public Roll setCycle(long rollCycle) {
            this.cycle.setOrderedValue(rollCycle);
            return this;
        }

        public Roll setNextCycleMetaPosition(long position) {
            this.nextCycleMetaPosition.setOrderedValue(position);
            return this;
        }

        public long getNextCycleMetaPosition() {
            return this.nextCycleMetaPosition.getVolatileValue();
        }

        public long getNextRollCycle() {
            return this.nextCycle.getVolatileValue();
        }

        public boolean casNextRollCycle(long rollCycle) {
            return this.nextCycle.compareAndSwapValue(-1, rollCycle);
        }
    }
}
