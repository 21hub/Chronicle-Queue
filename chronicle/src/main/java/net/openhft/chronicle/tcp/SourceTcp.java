/*
 * Copyright 2014 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.tcp;

import net.openhft.chronicle.*;
import net.openhft.lang.model.constraints.NotNull;
import net.openhft.lang.thread.LightPauser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class SourceTcp {
    protected final Logger logger;
    protected final String name;
    protected final AtomicBoolean running;
    protected final ChronicleQueueBuilder.ReplicaChronicleQueueBuilder builder;
    protected final ThreadPoolExecutor executor;
    protected final LightPauser pauser = new LightPauser(10000, 10000);

    protected SourceTcp(String name, final ChronicleQueueBuilder.ReplicaChronicleQueueBuilder builder, ThreadPoolExecutor executor) {
        this.builder = builder;
        this.name = ChronicleTcp.connectionName(name, this.builder.bindAddress(), this.builder.connectAddress());
        this.logger = LoggerFactory.getLogger(this.name);
        this.running = new AtomicBoolean(false);
        this.executor = executor;
    }

    public SourceTcp open() {
        this.running.set(true);
        this.executor.execute(createHandler());

        return this;
    }

    public boolean close()  {
        running.set(false);
        executor.shutdown();

        try {
            executor.awaitTermination(
                builder.selectTimeout() * 2,
                builder.selectTimeoutUnit());
        } catch(InterruptedException e) {
            // Ignored
        }

        return !running.get();
    }

    @Override
    public String toString() {
        return this.name;
    }

    public abstract boolean isLocalhost();
    protected abstract Runnable createHandler();

    /**
     * Creates a session handler according to the Chronicle the sources is connected to.
     *
     * @param socketChannel     The {@link java.nio.channels.SocketChannel}
     * @return                  The Runnable
     */
    protected Runnable createSessionHandler(final @NotNull SocketChannel socketChannel) {
        final Chronicle chronicle = builder.chronicle();
        if(chronicle != null) {
            if(chronicle instanceof IndexedChronicle) {
                return new IndexedSessionHandler(socketChannel);
            } else if(chronicle instanceof VanillaChronicle) {
                return new VanillaSessionHandler(socketChannel);
            } else {
                throw new IllegalStateException("Chronicle must be Indexed or Vanilla");
            }
        }

        throw new IllegalStateException("Chronicle can't be null");
    }

    // *************************************************************************
    //
    // *************************************************************************

    /**
     * Abstract class for Indexed and Vanilla chronicle replicaton
     */
    private abstract class SessionHandler implements Runnable, Closeable {
        private final SocketChannel socketChannel;

        private long lastUnpausedNS;

        protected final TcpConnection connection;
        protected ExcerptTailer tailer;
        protected ExcerptAppender appender;
        protected long lastHeartbeat;

        protected final ByteBuffer writeBuffer;
        protected final ByteBuffer readBuffer;

        private SessionHandler(final @NotNull SocketChannel socketChannel) {
            this.socketChannel = socketChannel;
            this.connection = new TcpConnection(socketChannel);
            this.tailer = null;
            this.appender = null;
            this.lastHeartbeat = 0;
            this.lastUnpausedNS = 0;
            this.readBuffer = ChronicleTcp.createBufferOfSize(builder.minBufferSize());
            this.writeBuffer = ChronicleTcp.createBuffer(builder.minBufferSize());

            this.readBuffer.clear();
            this.readBuffer.limit(0);

            this.writeBuffer.clear();
            this.writeBuffer.limit(0);
        }

        @Override
        public void close() throws IOException {
            if(this.tailer != null) {
                this.tailer.close();
                this.tailer = null;
            }

            if(this.appender != null) {
                this.appender.close();
                this.appender = null;
            }

            if(this.socketChannel.isOpen()) {
                this.socketChannel.close();
            }
        }

        @Override
        public void run() {
            VanillaSelectionKeySet selectionKeys = null;

            try {
                socketChannel.configureBlocking(false);
                socketChannel.socket().setTcpNoDelay(true);
                socketChannel.socket().setSoTimeout(0);
                socketChannel.socket().setSoLinger(false, 0);

                if(builder.receiveBufferSize() > 0) {
                    socketChannel.socket().setReceiveBufferSize(builder.receiveBufferSize());
                }
                if(builder.sendBufferSize() > 0) {
                    socketChannel.socket().setSendBufferSize(builder.sendBufferSize());
                }

                final VanillaSelector selector = new VanillaSelector()
                    .open()
                    .register(socketChannel, SelectionKey.OP_READ);

                tailer   = builder.chronicle().createTailer();
                appender = builder.chronicle().createAppender();

                selectionKeys = selector.vanillaSelectionKeys();

                if(selectionKeys != null) {
                    vanillaNioLoop(selector, selectionKeys);
                } else {
                    nioLoop(selector);
                }
            } catch (EOFException e) {
                if (running.get()) {
                    logger.info("Connection {} died", socketChannel);
                }
            } catch (Exception e) {
                if (running.get()) {
                    String msg = e.getMessage();
                    if (msg != null &&
                        (msg.contains("reset by peer")
                            || msg.contains("Broken pipe")
                            || msg.contains("was aborted by"))) {
                        logger.info("Connection {} closed from the other end: ", socketChannel, e.getMessage());
                    } else {
                        logger.info("Connection {} died", socketChannel, e);
                    }
                }
            } finally {
                if(selectionKeys != null) {
                    selectionKeys.clear();
                }
            }

            try {
                close();
            } catch(IOException e) {
                logger.warn("",e);
            }
        }

        private void vanillaNioLoop(final VanillaSelector selector, final VanillaSelectionKeySet selectionKeys) throws IOException {
            final int spinLoopCount = builder.selectorSpinLoopCount();
            final long selectTimeout = builder.selectTimeout();

            while(running.get()) {
                int nbKeys = selector.select(spinLoopCount, selectTimeout);

                if (nbKeys > 0) {
                    final SelectionKey[] keys = selectionKeys.keys();
                    final int size = selectionKeys.size();

                    for (int k = 0; k < size; k++) {
                        final SelectionKey key = keys[k];
                        if (key != null) {
                            if (!onSelectionKey(key)) {
                                break;
                            }
                        }
                    }

                    selectionKeys.clear();
                }
            }
        }

        private void nioLoop(final VanillaSelector selector) throws IOException {
            final int spinLoopCount = builder.selectorSpinLoopCount();
            final long selectTimeout = builder.selectTimeout();

            while(running.get()) {
                int nbKeys = selector.select(spinLoopCount, selectTimeout);

                if(nbKeys > 0) {
                    final Set<SelectionKey> keys = selector.selectionKeys();
                    for(final SelectionKey key : keys) {
                        if(!onSelectionKey(key)) {
                            break;
                        }
                    }

                    keys.clear();
                }
            }
        }

        protected boolean hasRoomForExcerpt(ByteBuffer buffer, ExcerptTailer tailer) {
            return (tailer.capacity() + ChronicleTcp.HEADER_SIZE) < (buffer.capacity() - buffer.position());
        }

        protected void pauseReset() {
            lastUnpausedNS = System.nanoTime();
            pauser.reset();
        }

        protected void pause() {
            if (lastUnpausedNS + ChronicleTcp.BUSY_WAIT_TIME_NS > System.nanoTime()) {
                return;
            }

            pauser.pause();
        }

        protected void setLastHeartbeat() {
            this.lastHeartbeat = System.currentTimeMillis() + builder.heartbeatIntervalMillis();
        }

        protected void setLastHeartbeat(long from) {
            this.lastHeartbeat = from + builder.heartbeatIntervalMillis();
        }

        protected void sendSizeAndIndex(int size, long index) throws IOException {
            writeBuffer.clear();
            writeBuffer.putInt(size);
            writeBuffer.putLong(index);
            writeBuffer.flip();
            connection.writeAllOrEOF(writeBuffer);
            setLastHeartbeat();
        }

        protected boolean onSelectionKey(final SelectionKey key) throws IOException {
            if (key != null) {
                if(key.isReadable()) {
                    if (!onRead(key)) {
                        return false;
                    }
                } else if(key.isWritable()) {
                    if (!onWrite(key)) {
                        return false;
                    }
                }
            }

            return true;
        }

        protected boolean onRead(final SelectionKey key) throws IOException {
            try {
                readBuffer.clear();
                readBuffer.limit(16);
                connection.readFullyOrEOF(readBuffer);
                readBuffer.flip();

                long    action = readBuffer.getLong();
                long    data   = readBuffer.getLong();
                boolean result = true;

                if (action == ChronicleTcp.ACTION_SUBSCRIBE) {
                    result = onSubscribe(key, data);
                } else if (action == ChronicleTcp.ACTION_QUERY) {
                    result = onQuery(key, data);
                } else if (action == ChronicleTcp.ACTION_UNSUBSCRIBE) {
                    result = onUnsubscribe(key, data);
                } else if (action == ChronicleTcp.ACTION_DATA) {
                    result = onData(key, data, true);
                } else if (action == ChronicleTcp.ACTION_DATA_NOACK) {
                    result = onData(key, data, false);
                } else {
                    throw new IOException("Unknown action received (" + action + ")");
                }

                if(!result) {
                    return false;
                }
            } catch(EOFException e) {
                key.selector().close();
                throw e;
            }

            return true;
        }

        protected boolean onWrite(final SelectionKey key) throws IOException {
            final long now = System.currentTimeMillis();
            if(running.get() && !write()) {
                if (lastHeartbeat <= now) {
                    sendSizeAndIndex(ChronicleTcp.IN_SYNC_LEN, ChronicleTcp.IDX_NONE);
                }
            }

            return true;
        }

        protected boolean onQuery(final SelectionKey key, long data) throws IOException {
            if(tailer.index(data)) {
                final long now = System.currentTimeMillis();
                setLastHeartbeat(now);

                while (true) {
                    if (tailer.nextIndex()) {
                        sendSizeAndIndex(ChronicleTcp.SYNC_IDX_LEN, tailer.index());
                        tailer.finish();
                        break;
                    } else {
                        if (lastHeartbeat <= now) {
                            sendSizeAndIndex(ChronicleTcp.IN_SYNC_LEN, ChronicleTcp.IDX_NONE);
                            break;
                        }
                    }
                }
            } else {
                sendSizeAndIndex(ChronicleTcp.IN_SYNC_LEN, 0L);
            }

            return true;
        }

        protected boolean onUnsubscribe(final SelectionKey key, long data) throws IOException {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);

            return true;
        }

        protected abstract boolean onSubscribe(final SelectionKey key, long data) throws IOException ;
        protected abstract boolean onData(final SelectionKey key, long size, boolean ack) throws IOException ;
        protected abstract boolean write() throws IOException;
    }

    /**
     * IndexedChronicle session handler
     */
    private class IndexedSessionHandler extends SessionHandler {
        private long index;

        private IndexedSessionHandler(final @NotNull SocketChannel socketChannel) {
            super(socketChannel);

            this.index = -1;
        }

        @Override
        protected boolean onSubscribe(final SelectionKey key, long data) throws IOException  {
            this.index = data;
            if (this.index == ChronicleTcp.IDX_TO_START) {
                this.index = -1;
            } else if (this.index == ChronicleTcp.IDX_TO_END) {
                this.index = tailer.toEnd().index();
            }

            sendSizeAndIndex(ChronicleTcp.SYNC_IDX_LEN, this.index);

            key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            return true;
        }

        @Override
        protected boolean onData(final SelectionKey key, long size, boolean ack) throws IOException {
            return true;
        }

        @Override
        protected boolean write() throws IOException {
            if (!tailer.index(index)) {
                if (tailer.wasPadding()) {
                    if (index >= 0) {
                        sendSizeAndIndex(ChronicleTcp.PADDED_LEN, tailer.index());
                    }

                    index++;
                }

                pause();

                if(running.get() && !tailer.index(index)) {
                    return false;
                }
            }

            pauseReset();

            /*
            writeBuffer.clear();

            int capacity  = writeBuffer.capacity();
            int maxExcerpts = builder.maxExcerptsPerMessage();
            for (int count = 0; count < maxExcerpts; ) {
                if(!tailer.wasPadding()) {
                    if (hasRoomForExcerpt(writeBuffer, tailer)) {
                        int size = (int)tailer.capacity();
                        writeBuffer.limit(writeBuffer.position() + size + ChronicleTcp.HEADER_SIZE);
                        writeBuffer.putInt(size);
                        writeBuffer.putLong(tailer.index());
                        tailer.read(writeBuffer);

                        if (count == 0 && (size > capacity / 2)) {
                            break;
                        }

                        count++;
                    } else {
                        break;
                    }
                }

                if((count < maxExcerpts) && !tailer.index(++index)) {
                    break;
                }
            }

            writeBuffer.flip();
            connection.writeAll(writeBuffer);

            if (writeBuffer.remaining() > 0) {
                throw new EOFException("Failed to send index=" + index);
            }

            index++;
            return true;
            */

            final long size = tailer.capacity();
            long remaining = size + ChronicleTcp.HEADER_SIZE;

            writeBuffer.clear();
            writeBuffer.putInt((int) size);
            writeBuffer.putLong(tailer.index());

            // for large objects send one at a time.
            if (size > writeBuffer.capacity() / 2) {
                while (remaining > 0) {
                    int size2 = (int) Math.min(remaining, writeBuffer.capacity());
                    writeBuffer.limit(size2);
                    tailer.read(writeBuffer);
                    writeBuffer.flip();
                    remaining -= writeBuffer.remaining();

                    connection.writeAll(writeBuffer);
                }
            } else {
                writeBuffer.limit((int) remaining);
                tailer.read(writeBuffer);
                for (int count = builder.maxExcerptsPerMessage(); (count > 0) && tailer.index(index + 1); ) {
                    if(!tailer.wasPadding()) {
                        if (hasRoomForExcerpt(writeBuffer, tailer)) {
                            // if there is free space, copy another one.
                            int size2 = (int) tailer.capacity();
                            writeBuffer.limit(writeBuffer.position() + size2 + ChronicleTcp.HEADER_SIZE);
                            writeBuffer.putInt(size2);
                            writeBuffer.putLong(tailer.index());
                            tailer.read(writeBuffer);

                            index++;
                            count--;
                        } else {
                            break;
                        }
                    } else {
                        index++;
                    }
                }

                writeBuffer.flip();
                connection.writeAll(writeBuffer);
            }

            index++;
            return true;
        }
    }

    /**
     * VanillaChronicle session handler
     */
    private class VanillaSessionHandler extends SessionHandler {
        private boolean nextIndex;
        private long index;

        private VanillaSessionHandler(final @NotNull SocketChannel socketChannel) {
            super(socketChannel);

            this.nextIndex = true;
            this.index = -1;
        }

        @Override
        protected boolean onSubscribe(final SelectionKey key, long data) throws IOException  {
            this.index = data;
            if (this.index == ChronicleTcp.IDX_TO_START) {
                this.nextIndex = true;
                this.tailer = tailer.toStart();
                this.index = -1;
            } else if (this.index == ChronicleTcp.IDX_TO_END) {
                this.nextIndex = false;
                this.tailer = tailer.toEnd();
                this.index = tailer.index();

                if(this.index == -1) {
                    this.nextIndex = true;
                    this.tailer = tailer.toStart();
                    this.index = -1;
                }
            } else {
                this.nextIndex = false;
            }

            sendSizeAndIndex(ChronicleTcp.SYNC_IDX_LEN, this.index);

            key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            return false;
        }

        @Override
        protected boolean onData(final SelectionKey key, long size, boolean ack) throws IOException {
            readBuffer.clear();
            readBuffer.limit((int) size);
            connection.readFullyOrEOF(readBuffer);
            readBuffer.flip();

            appender.startExcerpt((int) size);
            appender.write(readBuffer);
            appender.finish();

            pauser.unpause();

            if(ack) {
                sendSizeAndIndex(ChronicleTcp.ACK_LEN, appender.lastWrittenIndex());
            }

            return true;
        }

        @Override
        protected boolean write() throws IOException {
            if(nextIndex) {
                if (!tailer.nextIndex()) {
                    pause();
                    if (running.get() && !tailer.nextIndex()) {
                        return false;
                    }
                }
            } else {
                if(!tailer.index(this.index)) {
                    return false;
                } else {
                    this.nextIndex = true;
                }
            }

            pauseReset();

            final long size = tailer.capacity();
            long remaining = size + ChronicleTcp.HEADER_SIZE;

            writeBuffer.clear();
            writeBuffer.putInt((int) size);
            writeBuffer.putLong(tailer.index());

            // for large objects send one at a time.
            if (size > writeBuffer.capacity() / 2) {
                while (remaining > 0) {
                    int size2 = (int) Math.min(remaining, writeBuffer.capacity());
                    writeBuffer.limit(size2);
                    tailer.read(writeBuffer);
                    writeBuffer.flip();
                    remaining -= writeBuffer.remaining();
                    connection.writeAll(writeBuffer);
                }
            } else {
                writeBuffer.limit((int) remaining);
                tailer.read(writeBuffer);
                for (int count = builder.maxExcerptsPerMessage(); (count > 0) && tailer.nextIndex(); ) {
                    if (!tailer.wasPadding()) {
                        if (hasRoomForExcerpt(writeBuffer, tailer)) {
                            // if there is free space, copy another one.
                            int size2 = (int) tailer.capacity();
                            writeBuffer.limit(writeBuffer.position() + size2 + ChronicleTcp.HEADER_SIZE);
                            writeBuffer.putInt(size2);
                            writeBuffer.putLong(tailer.index());
                            tailer.read(writeBuffer);

                            count--;
                        } else {
                            break;
                        }
                    } else {
                        throw new AssertionError("Entry should not be padding - remove");
                    }
                }

                writeBuffer.flip();
                connection.writeAll(writeBuffer);
            }

            if (writeBuffer.remaining() > 0) {
                throw new EOFException("Failed to send index=" + tailer.index());
            }

            return true;
        }
    }
}
