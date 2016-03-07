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
package net.openhft.chronicle.queue;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.WriteMarshallable;
import org.jetbrains.annotations.NotNull;

/**
 * The component that facilitates sequentially writing data to a {@link ChronicleQueue}.
 *
 * @author peter.lawrey
 */
public interface ExcerptAppender extends ExcerptCommon {

    DocumentContext writingDocument();

    /**
     * @param writer to write to excerpt.
     * @return the index last written or -1 if a buffered appender is being used
     */
    void writeDocument(@NotNull WriteMarshallable writer);

    /**
     * @param marshallable to write to excerpt.
     * @return the index last written or -1 if a buffered appender is being used
     */
    void writeBytes(@NotNull WriteBytesMarshallable marshallable);

    /**
     * @param bytes to write to excerpt.
     * @return the index last written -1 if a buffered appender is being used
     */
    void writeBytes(@NotNull Bytes<?> bytes);

    /**
     * @return the index last written, this index includes the cycle and the sequence number
     * @throws IllegalStateException if no index is available
     */
    long lastIndexAppended();

    /**
     * @return the cycle this tailer is on, usually with chronicle-queue each cycle will have
     * its own unique data file to store the excerpt
     */
    long cycle();

}
