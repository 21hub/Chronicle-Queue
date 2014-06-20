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
import net.openhft.lang.model.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;

public abstract class AbstractInMemoryChronicleSynk implements Chronicle {

    private final ChronicleSynkConfig config;
    private final InetSocketAddress address;
    private final Logger logger;
    private final List<SynkConnector> connectors;

    private volatile boolean closed;

    public AbstractInMemoryChronicleSynk(String hostName, int port) {
        this(new InetSocketAddress(hostName, port), ChronicleSynkConfig.DEFAULT.clone());
    }

    public AbstractInMemoryChronicleSynk(@NotNull final InetSocketAddress address) {
        this(address, ChronicleSynkConfig.DEFAULT.clone());
    }

    public AbstractInMemoryChronicleSynk(@NotNull final InetSocketAddress address, @NotNull final ChronicleSynkConfig config) {
        this.config = config;
        this.address = address;
        this.logger = LoggerFactory.getLogger(getClass().getName() + '.' + address.toString());
        this.connectors = new LinkedList<SynkConnector>();
        this.closed = false;
    }

    @Override
    public String name() {
        return address.toString();
    }

    @Override
    public void close() {
        closed = true;

        for(SynkConnector connector : connectors) {
            try {
                connector.close();
            } catch (IOException e) {
                logger.warn("Error closing Sink", e);
            }
        }

        connectors.clear();
    }

    // *************************************************************************
    //
    // *************************************************************************

    protected SynkConnector newConnector() {
        if(closed) {
            throw new IllegalStateException("Chronicle is closed");
        }

        final SynkConnector connector = new SynkConnector();

        connectors.add(connector);

        return connector;
    }

    // *************************************************************************
    //
    // *************************************************************************

    protected class SynkConnector implements Closeable {
        private final ByteBuffer buffer;
        private SocketChannel channel;

        public SynkConnector() {
            this.buffer = TcpUtil.createBuffer(config.minBufferSize(), ByteOrder.nativeOrder());
        }

        public void open() {
            while (!closed) {
                try {
                    buffer.clear();
                    buffer.limit(0);

                    channel = SocketChannel.open(address);
                    channel.socket().setReceiveBufferSize(config.minBufferSize());
                    logger.info("Connected to " + address);
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

        public boolean read(int minSize) throws IOException {
            if(!closed) {
                while (buffer.position() < minSize) {
                    if (channel.read(buffer) < 0) {
                        channel.close();
                        return false;
                    }
                }
            }

            return !closed;
        }

        @Override
        public void close() throws IOException {
            if (channel != null) {
                try {
                    channel.close();
                    channel = null;
                } catch (IOException e) {
                    logger.warn("Error closing socket", e);
                }
            }
        }

        public boolean isOpen() {
            return !closed &&  channel.isOpen();
        }
    }
}
