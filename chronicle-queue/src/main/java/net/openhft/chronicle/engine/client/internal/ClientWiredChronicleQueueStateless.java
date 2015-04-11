package net.openhft.chronicle.engine.client.internal;

import net.openhft.chronicle.engine.client.ClientWiredStatelessTcpConnectionHub;
import net.openhft.chronicle.engine.client.ParameterizeWireKey;
import net.openhft.chronicle.map.AbstactStatelessClient;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.Excerpt;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.wire.ValueOut;
import net.openhft.chronicle.wire.WireKey;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Created by Rob Austin
 */
public class ClientWiredChronicleQueueStateless extends AbstactStatelessClient implements ChronicleQueue {

    public ClientWiredChronicleQueueStateless(ClientWiredStatelessTcpConnectionHub hub, String name) {
        super(name, hub);
        this.csp = "//" + name + "#QUEUE";
    }

    @Override
    public String name() {
        throw new UnsupportedOperationException("todo");
    }

    @NotNull
    @Override
    public Excerpt createExcerpt() throws IOException {
        throw new UnsupportedOperationException("todo");
    }

    @NotNull
    @Override
    public ExcerptTailer createTailer() throws IOException {
        throw new UnsupportedOperationException("todo");

    }

    @NotNull
    @Override
    public ExcerptAppender createAppender() throws IOException {
        throw new UnsupportedOperationException("todo");

    }

    @Override
    public long size() {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public long firstAvailableIndex() {
        throw new UnsupportedOperationException("todo");
    }


    @Override
    public void close() throws IOException {
        // todo add ref count
    }

    public long lastWrittenIndex() {
        return proxyReturnLong(EventId.lastWrittenIndex);
    }


    @Override
    protected Consumer<ValueOut> toParameters(@NotNull ParameterizeWireKey eventId, Object... args) {
        throw new UnsupportedOperationException("todo");
    }

    enum EventId implements ParameterizeWireKey {
        lastWrittenIndex;

        private final WireKey[] params;

        <P extends WireKey> EventId(P... params) {
            this.params = params;
        }

        public <P extends WireKey> P[] params() {
            return (P[]) this.params;
        }
    }

}
