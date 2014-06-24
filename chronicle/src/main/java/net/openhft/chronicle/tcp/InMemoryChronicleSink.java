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

import net.openhft.chronicle.Chronicle;
import net.openhft.chronicle.Excerpt;
import net.openhft.chronicle.ExcerptAppender;
import net.openhft.chronicle.ExcerptTailer;
import net.openhft.lang.io.NativeBytes;
import net.openhft.lang.model.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.nio.ch.DirectBuffer;

import java.io.IOException;
import java.io.StreamCorruptedException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;

public class InMemoryChronicleSink implements Chronicle {

    public enum ChronicleType {
        INDEXED,
        VANILLA
    }

    private final ChronicleSinkConfig config;
    private final InetSocketAddress address;
    private final Logger logger;
    private final List<ExcerptTailer> excerpts;
    private final ChronicleType chronicleType;

    private volatile boolean closed;

    public InMemoryChronicleSink(final ChronicleType type, @NotNull String host, int port) {
        this(type, new InetSocketAddress(host, port), ChronicleSinkConfig.DEFAULT);
    }

    public InMemoryChronicleSink(final ChronicleType type, @NotNull String host, int port, @NotNull final ChronicleSinkConfig config) {
        this(type, new InetSocketAddress(host, port), config);
    }

    public InMemoryChronicleSink(final ChronicleType type, @NotNull final InetSocketAddress address) {
        this(type, address, ChronicleSinkConfig.DEFAULT);
    }

    public InMemoryChronicleSink(final ChronicleType type, @NotNull final InetSocketAddress address, @NotNull final ChronicleSinkConfig config) {
        this.config = config;
        this.address = address;
        this.logger = LoggerFactory.getLogger(getClass().getName() + "@" + address.toString());
        this.excerpts = new LinkedList<ExcerptTailer>();
        this.closed = false;
        this.chronicleType = type;
    }


    @Override
    public String name() {
        return getClass().getName() + "@" + address.toString();
    }

    @Override
    public void close() throws IOException {
        if(!closed) {
            closed = true;

            for (ExcerptTailer excerpt : excerpts) {
                excerpt.close();
            }

            excerpts.clear();
        }
    }

    @Override
    public void clear() {
        try {
            close();
        } catch (IOException e) {
            logger.warn("Error closing Sink", e);
        }
    }

    @Override
    public Excerpt createExcerpt() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ExcerptAppender createAppender() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public long lastWrittenIndex() {
        return 0;
    }

    @Override
    public long size() {
        return 0;
    }

    @Override
    public ExcerptTailer createTailer() throws IOException {
        ExcerptTailer tailer = this.chronicleType == ChronicleType.INDEXED
            ? new InMemoryIndexedExcerptTailer()
            : new InMemoryVanillaExcerptTailer();

        excerpts.add(tailer);

        return tailer;
    }

    // *************************************************************************
    // Excerpt
    // *************************************************************************

    private abstract class AbstractInMemoryExcerpt extends NativeBytes implements ExcerptTailer {
        protected final Logger logger;
        protected long index;
        protected int lastSize;
        protected final ByteBuffer buffer;
        private SocketChannel channel;

        public AbstractInMemoryExcerpt() {
            super(NO_PAGE, NO_PAGE);

            this.buffer = TcpUtil.createBuffer(config.minBufferSize(), ByteOrder.nativeOrder());
            this.startAddr = ((DirectBuffer) this.buffer).address();
            this.capacityAddr = this.startAddr + config.minBufferSize();
            this.index = -1;
            this.lastSize = 0;
            this.logger = LoggerFactory.getLogger(getClass().getName() + "@" + address.toString());
            this.channel = null;
        }

        @Override
        public boolean index(long index) {
            if(!isChannelOpen()) {
                this.index = index;
                this.lastSize = 0;

                openChannel();
                writeToChannel(ByteBuffer.allocate(8).putLong(0, this.index));

                try {
                    if (!readFromChannel(8)) {
                        return false;
                    }

                    long receivedIndex = buffer.getLong();
                    if (this.index == -1 && receivedIndex != 0) {
                        if (receivedIndex != index) {
                            throw new IllegalStateException("Expected index " + index + " but got " + receivedIndex);
                        }
                    }

                    this.index = receivedIndex;
                } catch (IOException e) {
                    logger.warn("",e);
                }
            }

            throw new UnsupportedOperationException();
        }

        @Override
        public void finish() {
            if(lastSize > 0) {
                buffer.position(buffer.position() + lastSize);
            }

            super.finish();
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
        }

        @Override
        public ExcerptTailer toStart() {
            index(-1);
            return this;
        }

        @Override
        public ExcerptTailer toEnd() {
            index(Long.MAX_VALUE);
            return this;
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
            return InMemoryChronicleSink.this;
        }

        protected void openChannel() {
            while (!closed) {
                try {
                    buffer.clear();
                    buffer.limit(0);

                    channel = SocketChannel.open(address);
                    channel.socket().setReceiveBufferSize(config.minBufferSize());
                    logger.info("Connected to " + address);

                    return;
                } catch (IOException e) {
                    logger.info("Failed to connect to {}, retrying", address, e);
                }

                try {
                    Thread.sleep(config.reconnectDelay());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        public boolean isChannelOpen() {
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

        protected boolean readFromChannel(int thresholdSize, int minSize) throws IOException {
            if(!closed) {
                if (buffer.remaining() < thresholdSize) {
                    if (buffer.remaining() == 0) {
                        buffer.clear();
                    } else {
                        buffer.compact();
                    }

                    return readFromChannel(minSize);
                }
            }

            return !closed;
        }

        protected boolean readFromChannel(int minSize) throws IOException {
            if(!closed) {
                while (buffer.position() < minSize) {
                    if (channel.read(buffer) < 0) {
                        channel.close();
                        return false;
                    }
                }

                buffer.flip();
            }

            return !closed;
        }
    }

    private final class InMemoryIndexedExcerptTailer extends AbstractInMemoryExcerpt {
        @Override
        public boolean nextIndex() {
            try {
                if(!isChannelOpen()) {
                    index(this.index);
                }

                int excerptSize = buffer.getInt();
                switch (excerptSize) {
                    case InProcessChronicleSource.IN_SYNC_LEN:
                    case InProcessChronicleSource.PADDED_LEN:
                        this.index++; //TODO: increment index on padded entry ?
                        return false;
                    default:
                        break;
                }

                if (excerptSize > 128 << 20 || excerptSize < 0) {
                    throw new StreamCorruptedException("Size was " + excerptSize);
                }

                if(buffer.remaining() < excerptSize) {
                    if(!readFromChannel(buffer.remaining() - excerptSize)) {
                        return false;
                    }
                }

                positionAddr = startAddr + buffer.position();
                limitAddr = startAddr + buffer.limit();
                lastSize = excerptSize;
                index++;
            } catch (IOException e) {
                close();
                return false;
            }

            return true;
        }
    }

    private final class InMemoryVanillaExcerptTailer extends AbstractInMemoryExcerpt {
        @Override
        public boolean nextIndex() {
            try {
                if(!isChannelOpen()) {
                    index(this.index);
                }

                if(!readFromChannel(TcpUtil.HEADER_SIZE + 8, TcpUtil.HEADER_SIZE + 8 + 8)) {
                    return false;
                }

                long excerptIndex = buffer.getLong();
                int excerptSize = buffer.getInt();

                if(excerptSize != InProcessChronicleSource.IN_SYNC_LEN) {
                    if (excerptSize > 128 << 20 || excerptSize < 0) {
                        throw new StreamCorruptedException("Size was " + excerptSize);
                    }

                    if (buffer.remaining() < excerptSize) {
                        if (!readFromChannel(buffer.remaining() - excerptSize)) {
                            return false;
                        }
                    }

                    positionAddr = startAddr + buffer.position();
                    limitAddr = startAddr + buffer.limit();
                    lastSize = excerptSize;
                    index = excerptIndex;
                } else {
                    // Heartbeat
                    return false;
                }
            } catch (IOException e) {
                close();
                return false;
            }

            return true;
        }
    }
}
