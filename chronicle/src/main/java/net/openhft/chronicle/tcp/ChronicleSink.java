/*
 * Copyright 2013 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.tcp;

import net.openhft.chronicle.*;
import net.openhft.chronicle.tools.WrappedExcerpt;
import net.openhft.lang.io.NativeBytes;
import net.openhft.lang.model.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.nio.ch.DirectBuffer;

import java.io.EOFException;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;

/**
 * This listens to a ChronicleSource and copies new entries. This Sink can be any number of excerpt behind the source
 * and can be restart many times without losing data.
 *
 * <p>Can be used as a component with lower over head than ChronicleSink
 *
 * @author peter.lawrey
 */
public class ChronicleSink implements Chronicle {
    @NotNull
    private final Chronicle chronicle;

    @NotNull
    private final ChronicleSinkConfig sinkConfig;

    @NotNull
    private final SocketAddress address;

    private final List<ExcerptCommon> excerpts;

    private final Logger logger;
    private volatile boolean closed = false;

    public ChronicleSink(String hostname, int port) throws IOException {
        this(null, ChronicleSinkConfig.DEFAULT, new InetSocketAddress(hostname, port));
    }

    public ChronicleSink(@NotNull Chronicle chronicle, String hostname, int port) throws IOException {
        this(chronicle, ChronicleSinkConfig.DEFAULT, new InetSocketAddress(hostname, port));
    }

    public ChronicleSink(@NotNull Chronicle chronicle, @NotNull final ChronicleSinkConfig config, String hostname, int port) throws IOException {
        this(chronicle, config, new InetSocketAddress(hostname, port));
    }

    public ChronicleSink(@NotNull final ChronicleSinkConfig config, String hostname, int port) throws IOException {
        this(null, config, new InetSocketAddress(hostname, port));
    }

    public ChronicleSink(@NotNull final InetSocketAddress address) throws IOException {
        this(null, ChronicleSinkConfig.DEFAULT, address);
    }

    public ChronicleSink(@NotNull final Chronicle chronicle, @NotNull final InetSocketAddress address) throws IOException {
        this(chronicle, ChronicleSinkConfig.DEFAULT, address);
    }

    public ChronicleSink(@NotNull final ChronicleSinkConfig sinkConfig, @NotNull final InetSocketAddress address) throws IOException {
        this(null, sinkConfig, address);
    }

    public ChronicleSink(@NotNull final Chronicle chronicle, @NotNull final ChronicleSinkConfig sinkConfig, @NotNull final InetSocketAddress address) throws IOException {
        this.chronicle = chronicle;
        this.sinkConfig = sinkConfig;
        this.address = address;
        this.logger = LoggerFactory.getLogger(getClass().getName() + '.' + address.getHostName() + '@' + address.getPort());
        this.excerpts = new LinkedList<ExcerptCommon>();
    }

    @Override
    public String name() {
        return chronicle.name();
    }

    @NotNull
    @Override
    public synchronized Excerpt createExcerpt() throws IOException {
        Excerpt excerpt = null;

        if(chronicle == null) {
            excerpt = new InMemoryExcerpt();
        } else {
            if (!excerpts.isEmpty()) {
                throw new UnsupportedOperationException("An Excerpt has already been created");
            }

            excerpt = (chronicle instanceof IndexedChronicle)
                ? new PersistentIndexedSinkExcerpt(chronicle.createExcerpt())
                : new PersistentVanillaSinkExcerpt(chronicle.createExcerpt());
        }

        if(excerpt != null) {
            excerpts.add(excerpt);
        }

        return excerpt;
    }

    @NotNull
    @Override
    public synchronized ExcerptTailer createTailer() throws IOException {
        ExcerptTailer excerpt = null;

        if(chronicle == null) {
            excerpt = new InMemoryExcerptTailer();
        } else {
            if (!excerpts.isEmpty()) {
                throw new UnsupportedOperationException("An Excerpt has already been created");
            }

            excerpt = (chronicle instanceof IndexedChronicle)
                ? new PersistentIndexedSinkExcerpt(chronicle.createTailer())
                : new PersistentVanillaSinkExcerpt(chronicle.createExcerpt());
        }

        if(excerpt != null) {
            excerpts.add(excerpt);
        }

        return excerpt;
    }

    @NotNull
    @Override
    public ExcerptAppender createAppender() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public long lastWrittenIndex() {
        return (chronicle != null) ? chronicle.lastWrittenIndex() : -1;
    }

    @Override
    public long size() {
        return (chronicle != null) ? chronicle.size() : 0;
    }

    @Override
    public void clear() {
        if(chronicle != null) {
            chronicle.clear();
        }
    }

    @Override
    public synchronized void close() {
        if(!closed) {
            closed = true;

            for (ExcerptCommon excerpt : excerpts) {
                excerpt.close();
            }

            try {
                if(chronicle != null) {
                    chronicle.close();
                }
            } catch (IOException e) {
                logger.warn("Error closing Sink", e);
            }
        }
    }

    public void checkCounts(int min, int max) {
        if(chronicle instanceof VanillaChronicle) {
            ((VanillaChronicle)chronicle).checkCounts(min, max);
        }
    }

    // *************************************************************************
    // TCP/DISK based replication
    // *************************************************************************

    private abstract class AbstractPersistentSinkExcerpt<T extends Chronicle>  extends WrappedExcerpt {
        protected final T chronicleImpl;
        protected final ByteBuffer buffer;
        private SocketChannel socketChannel;
        protected long lastLocalIndex;

        public AbstractPersistentSinkExcerpt(ExcerptCommon excerptCommon) {
            super(excerptCommon);
            this.chronicleImpl = (T)chronicle;
            this.socketChannel = null;
            this.buffer = TcpUtil.createBuffer(sinkConfig.minBufferSize(), ByteOrder.nativeOrder());
            this.lastLocalIndex = -1;
        }

        @Override
        public boolean nextIndex() {
            return super.nextIndex() || (readNext() && super.nextIndex());
        }

        @Override
        public boolean index(long index) throws IndexOutOfBoundsException {
            return super.index(index) || (index >= 0 && readNext() && super.index(index));
        }

        @Override
        public synchronized void close() {
            if(socketChannel != null) {
                try {
                    socketChannel.close();
                } catch (IOException e) {
                    logger.warn("Error closing socket", e);
                }
            }

            excerpts.remove(this);

            super.close();
        }

        private boolean readNext() {
            if (socketChannel == null || !socketChannel.isOpen()) {
                socketChannel = createConnection();
            }

            return socketChannel != null && readNextExcerpt(socketChannel);
        }

        private SocketChannel createConnection() {
            while (!closed) {
                try {
                    buffer.clear();
                    buffer.limit(0);

                    SocketChannel sc = SocketChannel.open(address);
                    sc.socket().setReceiveBufferSize(sinkConfig.minBufferSize());
                    logger.info("Connected to " + address);

                    lastLocalIndex = lastIndex();

                    ByteBuffer bb = ByteBuffer.allocate(8);
                    bb.putLong(0, lastLocalIndex);
                    TcpUtil.writeAllOrEOF(sc, bb);

                    return sc;
                } catch (IOException e) {
                    logger.info("Failed to connect to {} retrying", address, e);
                }

                try {
                    Thread.sleep(sinkConfig.reconnectDelay());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
        }

        protected abstract long lastIndex();
        protected abstract boolean readNextExcerpt(final SocketChannel channel);
    }

    /**
     * IndexedChronicle sink
     */
    private final class PersistentIndexedSinkExcerpt extends AbstractPersistentSinkExcerpt<IndexedChronicle> {
        @NotNull
        private final ExcerptAppender appender;

        @SuppressWarnings("unchecked")
        public PersistentIndexedSinkExcerpt(@NotNull final ExcerptCommon excerpt) throws IOException {
            super(excerpt);
            this.appender = chronicle.createAppender();
        }

        @Override
        protected long lastIndex()  {
            return chronicle.lastWrittenIndex();
        }

        @Override
        protected boolean readNextExcerpt(@NotNull final SocketChannel sc) {
            try {
                if (closed) return false;

                if (buffer.remaining() < TcpUtil.HEADER_SIZE) {
                    if (buffer.remaining() == 0) {
                        buffer.clear();
                    } else {
                        buffer.compact();
                    }

                    int minSize = TcpUtil.HEADER_SIZE + 8;
                    while (buffer.position() < minSize) {
                        if (sc.read(buffer) < 0) {
                            sc.close();
                            return false;
                        }
                    }
                    buffer.flip();
                }

                int size = buffer.getInt();
                switch (size) {
                    case ChronicleSource.IN_SYNC_LEN:
                        buffer.getLong();
                        return false;
                    case ChronicleSource.PADDED_LEN:
                        appender.startExcerpt(((IndexedChronicle) chronicle).config().dataBlockSize() - 1);
                        buffer.getLong();
                        return true;
                    case ChronicleSource.SYNC_IDX_LEN:
                        buffer.getLong();
                        //Sync IDX message, re-try
                        return readNextExcerpt(sc);
                    default:
                        break;
                }

                if (size > 128 << 20 || size < 0)
                    throw new StreamCorruptedException("size was " + size);

                final long scIndex = buffer.getLong();
                if (scIndex != chronicle.size()) {
                    throw new StreamCorruptedException("Expected index " + chronicle.size() + " but got " + scIndex);
                }

                appender.startExcerpt(size);
                // perform a progressive copy of data.
                long remaining = size;
                int limit = buffer.limit();

                int size2 = (int) Math.min(buffer.remaining(), remaining);
                remaining -= size2;
                buffer.limit(buffer.position() + size2);
                appender.write(buffer);
                // reset the limit;
                buffer.limit(limit);

                // needs more than one read.
                while (remaining > 0) {
                    buffer.clear();
                    int size3 = (int) Math.min(buffer.capacity(), remaining);
                    buffer.limit(size3);
                    if (sc.read(buffer) < 0)
                        throw new EOFException();
                    buffer.flip();
                    remaining -= buffer.remaining();
                    appender.write(buffer);
                }

                appender.finish();
            } catch (IOException e) {
                logger.info("Lost connection to {} retrying", address, e);
                try {
                    sc.close();
                } catch (IOException ignored) {
                }
            }
            return true;
        }
    }

    /**
     * VanillaChronicle sink
     */
    private final class PersistentVanillaSinkExcerpt extends AbstractPersistentSinkExcerpt<VanillaChronicle> {
        @NotNull
        private final VanillaChronicle.VanillaAppender appender;

        @SuppressWarnings("unchecked")
        public PersistentVanillaSinkExcerpt(@NotNull final ExcerptCommon excerpt) throws IOException {
            super(excerpt);
            this.appender = chronicleImpl.createAppender();
        }

        @Override
        protected long lastIndex()  {
            return chronicleImpl.lastIndex();
        }

        @Override
        protected boolean readNextExcerpt(@NotNull final SocketChannel sc) {
            try {
                if (closed) {
                    return false;
                }

                // Check if there is enogh data (header plus some more data)
                if (buffer.remaining() < TcpUtil.HEADER_SIZE) {
                    if (buffer.remaining() == 0){
                        buffer.clear();
                    } else {
                        buffer.compact();
                    }

                    // Waith till some more data has been readed
                    while (buffer.position() < TcpUtil.HEADER_SIZE + 8) {
                        if (sc.read(buffer) < 0) {
                            sc.close();
                            return false;
                        }
                    }

                    buffer.flip();
                }

                int size = buffer.getInt();
                long scIndex = buffer.getLong();

                switch (size) {
                    case ChronicleSource.IN_SYNC_LEN:
                        //Heartbeat message ignore and return false
                        return false;
                    case ChronicleSource.PADDED_LEN:
                        //Padded message, should not happen
                        return false;
                    case ChronicleSource.SYNC_IDX_LEN:
                        //Sync IDX message, re-try
                        return readNextExcerpt(sc);
                    default:
                        break;
                }

                if (size > 128 << 20 || size < 0) {
                    throw new StreamCorruptedException("size was " + size);
                }

                if(lastLocalIndex != scIndex) {

                    int cycle = (int) (scIndex >>> chronicleImpl.getEntriesForCycleBits());

                    appender.startExcerpt(size, cycle);
                    // perform a progressive copy of data.
                    long remaining = size;
                    int limit = buffer.limit();
                    int size2 = (int) Math.min(buffer.remaining(), remaining);
                    remaining -= size2;
                    buffer.limit(buffer.position() + size2);
                    appender.write(buffer);
                    // reset the limit;
                    buffer.limit(limit);

                    // needs more than one read.
                    while (remaining > 0) {
                        buffer.clear();
                        int size3 = (int) Math.min(buffer.capacity(), remaining);
                        buffer.limit(size3);
                        if (sc.read(buffer) < 0)
                            throw new EOFException();
                        buffer.flip();
                        remaining -= buffer.remaining();
                        appender.write(buffer);
                    }

                    appender.finish();
                } else {
                    buffer.position(buffer.position() + size);
                    return readNextExcerpt(sc);
                }
            } catch (IOException e) {
                logger.info("Lost connection to {} retrying ",address, e);
                try {
                    sc.close();
                } catch (IOException ignored) {
                }
            }

            return true;
        }
    }


    // *************************************************************************
    // TCP/MEMORY based replication
    // *************************************************************************

    private class InMemoryExcerptTailer extends NativeBytes implements ExcerptTailer {
        protected final Logger logger;
        protected long index;
        protected int lastSize;
        protected final ByteBuffer buffer;
        private SocketChannel channel;

        public InMemoryExcerptTailer() {
            super(NO_PAGE, NO_PAGE);

            this.buffer = TcpUtil.createBuffer(sinkConfig.minBufferSize(), ByteOrder.nativeOrder());
            this.startAddr = ((DirectBuffer) this.buffer).address();
            this.capacityAddr = this.startAddr + sinkConfig.minBufferSize();
            this.index = -1;
            this.lastSize = 0;
            this.logger = LoggerFactory.getLogger(getClass().getName() + "@" + address.toString());
            this.channel = null;
        }

        @Override
        public void finish() {
            if(!isFinished()) {
                if (lastSize > 0) {
                    buffer.position(buffer.position() + lastSize);
                }

                super.finish();
            }
        }

        @Override
        public void close() {
            if (channel != null) {
                try {
                    channel.close();
                    channel = null;
                } catch (IOException e) {
                    logger.warn("Error closing socket", e);
                }
            }

            excerpts.remove(this);
        }

        @Override
        public boolean wasPadding() {
            return false;
        }

        @Override
        public long index() {
            return index;
        }

        @Override
        public long lastWrittenIndex() {
            return index();
        }

        @Override
        public Chronicle chronicle() {
            return ChronicleSink.this;
        }

        @Override
        public ExcerptTailer toStart() {
            index(-1);
            return this;
        }

        @Override
        public ExcerptTailer toEnd() {
            index(-2);
            return this;
        }

        @Override
        public boolean index(long index) {
            this.index = index;
            this.lastSize = 0;

            try {
                if(!isChannelOpen()) {
                    openChannel();
                }

                if (writeToChannel(ByteBuffer.allocate(8).putLong(0, this.index))) {
                    while (readFromChannel(TcpUtil.HEADER_SIZE)) {
                        int receivedSize = buffer.getInt();
                        long receivedIndex = buffer.getLong();

                        switch(receivedSize) {
                            case ChronicleSource.SYNC_IDX_LEN:
                                if(index == -1) {
                                    return receivedIndex == -1;
                                } else if(index == -2) {
                                    return advanceIndex();
                                } else {
                                    return (index == receivedIndex) ? advanceIndex() : false;
                                }
                            case ChronicleSource.PADDED_LEN:
                            case ChronicleSource.IN_SYNC_LEN:
                                return false;
                        }

                        if (buffer.remaining() >= receivedSize) {
                            buffer.position(buffer.position() + receivedSize);
                        }
                    }
                }
            } catch (IOException e) {
                logger.warn("",e);
            }

            return false;
        }

        @Override
        public boolean nextIndex() {
            try {
                if(!isChannelOpen()) {
                    return index(this.index);
                }

                if(!readFromChannel(TcpUtil.HEADER_SIZE + 8)) {
                    return false;
                }

                int excerptSize = buffer.getInt();
                long receivedIndex = buffer.getLong();

                switch (excerptSize) {
                    case ChronicleSource.IN_SYNC_LEN:
                    case ChronicleSource.PADDED_LEN:
                        return false;
                    case ChronicleSource.SYNC_IDX_LEN:
                        return nextIndex();
                }

                if (excerptSize > 128 << 20 || excerptSize < 0) {
                    throw new StreamCorruptedException("Size was " + excerptSize);
                }

                if(buffer.remaining() < excerptSize) {
                    if(!readFromChannel(buffer.remaining() - excerptSize)) {
                        return false;
                    }
                }

                index = receivedIndex;
                positionAddr = startAddr + buffer.position();
                limitAddr = startAddr + buffer.limit();
                lastSize = excerptSize;
                finished = false;
            } catch (IOException e) {
                close();
                return false;
            }

            return true;
        }

        protected void openChannel() {
            while (!closed) {
                try {
                    buffer.clear();
                    buffer.limit(0);

                    channel = SocketChannel.open(address);
                    channel.socket().setReceiveBufferSize(sinkConfig.minBufferSize());
                    logger.info("Connected to " + address);

                    return;
                } catch (IOException e) {
                    logger.info("Failed to connect to {}, retrying", address, e);
                }

                try {
                    Thread.sleep(sinkConfig.reconnectDelay());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        protected boolean isChannelOpen() {
            return !closed && channel!= null && channel.isOpen();
        }

        protected boolean writeToChannel(final ByteBuffer buffer) {
            try {
                TcpUtil.writeAllOrEOF(channel, buffer);
            } catch (IOException e) {
                return false;
            }

            return true;
        }

        protected boolean readFromChannel(int size) throws IOException {
            if(!closed) {
                if (buffer.remaining() < size) {
                    if (buffer.remaining() == 0) {
                        buffer.clear();
                    } else {
                        buffer.compact();
                    }

                    while (buffer.position() < size) {
                        if (channel.read(buffer) < 0) {
                            channel.close();
                            return false;
                        }
                    }

                    buffer.flip();
                }
            }

            return !closed;
        }

        protected boolean advanceIndex() throws IOException {
            if(nextIndex()) {
                finish();
                return true;
            } else {
                return false;
            }
        }
    }

    private class InMemoryExcerpt extends InMemoryExcerptTailer implements Excerpt {
        @Override
        public long findMatch(@NotNull ExcerptComparator comparator) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void findRange(@NotNull long[] startEnd, @NotNull ExcerptComparator comparator) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Excerpt toStart() {
            super.toStart();
            return this;
        }

        @Override
        public Excerpt toEnd() {
            super.toEnd();
            return this;
        }
    }
}
