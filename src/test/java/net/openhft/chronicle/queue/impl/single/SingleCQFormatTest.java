/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.MappedBytes;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.io.IOTools;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.core.threads.ThreadDump;
import net.openhft.chronicle.queue.*;
import net.openhft.chronicle.queue.micros.Order;
import net.openhft.chronicle.queue.micros.Side;
import net.openhft.chronicle.wire.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

/**
 * Created by Peter on 05/03/2016.
 */
public class SingleCQFormatTest {
    private static final int TEST_CHUNK_SIZE = 4 << 20; // 4 MB

    static {
        // init class
        SingleChronicleQueueBuilder.init();
    }

    int appendMode;
    private ThreadDump threadDump;

    public static void assertHexEquals(long a, long b) {
        if (a != b)
            assertEquals(Long.toHexString(a) + " != " + Long.toHexString(b), a, b);
    }

    public static void expected(ExcerptTailer tailer, String expected) {
        try (DocumentContext dc = tailer.readingDocument()) {
            assertTrue(dc.isPresent());
            Bytes bytes2 = Bytes.allocateDirect(128);
            dc.wire().copyTo(new TextWire(bytes2));
            assertEquals(expected, bytes2.toString());
        }
    }

    @Test
    public void testEmptyDirectory() {
        File dir = new File(OS.TARGET + "/deleteme-" + System.nanoTime());
        dir.mkdir();
        SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(dir).build();
        assertEquals(Integer.MAX_VALUE, queue.firstCycle());
        assertEquals(Long.MAX_VALUE, queue.firstIndex());
        assertEquals(Integer.MIN_VALUE, queue.lastCycle());
        final long firstOfDay = RollCycles.DAILY.toIndex(RollCycles.DAILY.current(System::currentTimeMillis, 0), 0L);
        assertEquals(firstOfDay, queue.nextIndexToWrite());
        queue.close();

        IOTools.shallowDeleteDirWithFiles(dir.getAbsolutePath());
    }

    @Test
    public void testInvalidFile() throws FileNotFoundException {
        File dir = new File(OS.TARGET + "/deleteme-" + System.nanoTime());
        dir.mkdir();

        MappedBytes bytes = MappedBytes.mappedBytes(new File(dir, "19700102" + SingleChronicleQueue.SUFFIX), TEST_CHUNK_SIZE);
        bytes.write8bit("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\" ?>");

        SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(dir)
                .blockSize(TEST_CHUNK_SIZE)
                .build();
        assertEquals(1, queue.firstCycle());
        assertEquals(1, queue.lastCycle());
        try {
            ExcerptTailer tailer = queue.createTailer();
            tailer.toEnd();
            fail();
        } catch (Exception e) {
            assertEquals("java.io.StreamCorruptedException: Unexpected magic number 783f3c37",
                    e.toString());
        }
        queue.close();
        try {
            IOTools.shallowDeleteDirWithFiles(dir.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testNoHeader() throws FileNotFoundException {
        File dir = new File(OS.TARGET + "/deleteme-" + System.nanoTime());
        dir.mkdir();

        MappedBytes bytes = MappedBytes.mappedBytes(new File(dir, "19700101" + SingleChronicleQueue.SUFFIX), TEST_CHUNK_SIZE);

        bytes.close();
        SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(dir)
                .blockSize(TEST_CHUNK_SIZE)
                .build();

        testQueue(queue);
        queue.close();
        try {
            IOTools.shallowDeleteDirWithFiles(dir.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // TODO add controls on how to wait, for how long and what action to take to fix it.
    @Test(expected = TimeoutException.class)
    @Ignore("Long running")
    public void testDeadHeader() throws FileNotFoundException {
        File dir = new File(OS.TARGET + "/deleteme-" + System.nanoTime());
        dir.mkdir();

        MappedBytes bytes = MappedBytes.mappedBytes(new File(dir, "19700101" + SingleChronicleQueue.SUFFIX), TEST_CHUNK_SIZE);
        bytes.writeInt(Wires.NOT_COMPLETE | Wires.META_DATA | Wires.UNKNOWN_LENGTH);
        bytes.close();
        SingleChronicleQueue queue = null;
        try {
            queue = SingleChronicleQueueBuilder.binary(dir)
                    .blockSize(TEST_CHUNK_SIZE)
                    .build();

            testQueue(queue);
        } finally {
            Closeable.closeQuietly(queue);
            IOTools.shallowDeleteDirWithFiles(dir.getAbsolutePath());
        }
    }

    public void testQueue(SingleChronicleQueue queue) {
        ExcerptTailer tailer = queue.createTailer();
        try (DocumentContext dc = tailer.readingDocument()) {
            assertFalse(dc.isPresent());
        }
    }

    @Test
    public void testCompleteHeader() throws FileNotFoundException {
        File dir = new File(OS.TARGET + "/deleteme-" + System.nanoTime());
        dir.mkdir();

        MappedBytes bytes = MappedBytes.mappedBytes(new File(dir, "19700101" + SingleChronicleQueue.SUFFIX), TEST_CHUNK_SIZE);
        Wire wire = new BinaryWire(bytes);
        try (DocumentContext dc = wire.writingDocument(true)) {
            dc.wire().writeEventName(() -> "header").typePrefix(SingleChronicleQueueStore.class).marshallable(w -> {
                w.write(() -> "wireType").object(WireType.BINARY);
                w.write(() -> "writePosition").int64forBinding(0);
                w.write(() -> "roll").typedMarshallable(new SingleChronicleQueueStore.Roll(RollCycles.DAILY, 0));
                w.write(() -> "indexing").typedMarshallable(new SingleChronicleQueueStore.Indexing(WireType.BINARY, 32 << 10, 32));
                w.write(() -> "lastAcknowledgedIndexReplicated").int64forBinding(0);
            });
        }

        assertEquals("--- !!meta-data #binary\n" +
                "header: !SCQStore {\n" +
                "  wireType: !WireType BINARY,\n" +
                "  writePosition: 0,\n" +
                "  roll: !SCQSRoll {\n" +
                "    length: 86400000,\n" +
                "    format: yyyyMMdd,\n" +
                "    epoch: 0\n" +
                "  },\n" +
                "  indexing: !SCQSIndexing {\n" +
                "    indexCount: !int 32768,\n" +
                "    indexSpacing: 32,\n" +
                "    index2Index: 0,\n" +
                "    lastIndex: 0\n" +
                "  },\n" +
                "  lastAcknowledgedIndexReplicated: 0\n" +
                "}\n", Wires.fromSizePrefixedBlobs(bytes.readPosition(0)));
        bytes.close();

        SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(dir)
                .blockSize(TEST_CHUNK_SIZE)
                .build();
        testQueue(queue);

        queue.close();
        try {
            IOTools.shallowDeleteDirWithFiles(dir.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testCompleteHeader2() throws FileNotFoundException {
        File dir = new File(OS.TARGET + "/deleteme-" + System.nanoTime());
        dir.mkdir();

        MappedBytes bytes = MappedBytes.mappedBytes(new File(dir, "19700101-02" + SingleChronicleQueue.SUFFIX), TEST_CHUNK_SIZE);
        Wire wire = new BinaryWire(bytes);
        try (DocumentContext dc = wire.writingDocument(true)) {
            dc.wire().writeEventName(() -> "header").typedMarshallable(
                    new SingleChronicleQueueStore(RollCycles.HOURLY, WireType.BINARY, bytes, 60 * 60 * 1000, 4 << 10, 4));
        }

        assertEquals("--- !!meta-data #binary\n" +
                "header: !SCQStore {\n" +
                "  wireType: !WireType BINARY,\n" +
                "  writePosition: 0,\n" +
                "  roll: !SCQSRoll {\n" +
                "    length: 3600000,\n" +
                "    format: yyyyMMdd-HH,\n" +
                "    epoch: 3600000\n" +
                "  },\n" +
                "  indexing: !SCQSIndexing {\n" +
                "    indexCount: !int 4096,\n" +
                "    indexSpacing: 4,\n" +
                "    index2Index: 0,\n" +
                "    lastIndex: 0\n" +
                "  },\n" +
                "  lastAcknowledgedIndexReplicated: 0\n" +
                "}\n", Wires.fromSizePrefixedBlobs(bytes.readPosition(0)));
        bytes.close();

        SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(dir)
                .blockSize(TEST_CHUNK_SIZE)
                .rollCycle(RollCycles.HOURLY)
                .build();
        testQueue(queue);
        assertEquals(2, queue.firstCycle());
        queue.close();
        try {
            IOTools.shallowDeleteDirWithFiles(dir.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testIncompleteHeader() throws FileNotFoundException {
        File dir = new File(OS.TARGET + "/deleteme-" + System.nanoTime());
        dir.mkdir();

        MappedBytes bytes = MappedBytes.mappedBytes(new File(dir, "19700101" + SingleChronicleQueue.SUFFIX), TEST_CHUNK_SIZE);
        Wire wire = new BinaryWire(bytes);
        try (DocumentContext dc = wire.writingDocument(true)) {
            dc.wire().writeEventName(() -> "header").typePrefix(SingleChronicleQueueStore.class).marshallable(w -> {
                w.write(() -> "wireType").object(WireType.BINARY);
            });
        }

        bytes.close();
        try (SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(dir)
                .blockSize(TEST_CHUNK_SIZE)
                .build()) {
            testQueue(queue);
            fail();
        } catch (Exception e) {
//            e.printStackTrace();
            assertEquals("net.openhft.chronicle.core.io.IORuntimeException: net.openhft.chronicle.core.io.IORuntimeException: field writePosition required",
                    e.toString());
        }
        try {
            IOTools.shallowDeleteDirWithFiles(dir.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testMyData() {
        File dir = new File(OS.TARGET + "/deleteme-" + System.nanoTime());
        ClassAliasPool.CLASS_ALIASES.addAlias(MyData.class);

        try (SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(dir).build()) {
            ExcerptAppender appender = queue.createAppender();
            try (DocumentContext dc = appender.writingDocument()) {
                MyData name = new MyData("name", 12345, 1.2, 111);
                System.out.println(name);
                name.writeMarshallable(dc.wire());

                MyData name2 = new MyData("name2", 12346, 1.3, 112);
                System.out.println(name2);
                name2.writeMarshallable(dc.wire());
            }
            System.out.println("\nThe queue");
            System.out.println(queue.dump());
        }
    }

    @Test
    public void testTwoMessages() throws FileNotFoundException {
        File dir = new File(OS.TARGET + "/deleteme-" + System.nanoTime());
        dir.mkdir();
        RollCycles cycle = RollCycles.DAILY;

        {
            MappedBytes mappedBytes = MappedBytes.mappedBytes(new File(dir, "19700102" + SingleChronicleQueue.SUFFIX), TEST_CHUNK_SIZE);
            Wire wire = new BinaryWire(mappedBytes);
            try (DocumentContext dc = wire.writingDocument(true)) {
                dc.wire().writeEventName(() -> "header").typedMarshallable(
                        new SingleChronicleQueueStore(cycle, WireType.BINARY, mappedBytes, 0, cycle.defaultIndexCount(), cycle.defaultIndexSpacing()));
            }
            try (DocumentContext dc = wire.writingDocument(false)) {
                dc.wire().writeEventName("msg").text("Hello world");
            }
            try (DocumentContext dc = wire.writingDocument(false)) {
                dc.wire().writeEventName("msg").text("Also hello world");
            }

            assertEquals("--- !!meta-data #binary\n" +
                    "header: !SCQStore {\n" +
                    "  wireType: !WireType BINARY,\n" +
                    "  writePosition: 0,\n" +
                    "  roll: !SCQSRoll {\n" +
                    "    length: 86400000,\n" +
                    "    format: yyyyMMdd,\n" +
                    "    epoch: 0\n" +
                    "  },\n" +
                    "  indexing: !SCQSIndexing {\n" +
                    "    indexCount: !int 16384,\n" +
                    "    indexSpacing: 16,\n" +
                    "    index2Index: 0,\n" +
                    "    lastIndex: 0\n" +
                    "  },\n" +
                    "  lastAcknowledgedIndexReplicated: 0\n" +
                    "}\n" +
                    "# position: 288\n" +
                    "--- !!data #binary\n" +
                    "msg: Hello world\n" +
                    "# position: 309\n" +
                    "--- !!data #binary\n" +
                    "msg: Also hello world\n", Wires.fromSizePrefixedBlobs(mappedBytes.readPosition(0)));
            mappedBytes.close();
        }

        SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(dir)
                .rollCycle(cycle)
                .blockSize(TEST_CHUNK_SIZE)
                .build();
        ExcerptTailer tailer = queue.createTailer();
        readTwo(tailer);
        tailer.toStart();
        readTwo(tailer);

        // TODO no direction
        tailer.direction(TailerDirection.NONE).toStart();

        long start = queue.firstIndex();
        assertEquals(start, tailer.index());
        expected(tailer, "msg: Hello world\n");
        assertEquals(start, tailer.index());
        expected(tailer, "msg: Hello world\n");

/* TODO FIX.
        assertEquals(start + 1, queue.lastIndex());

        tailer.direction(TailerDirection.BACKWARD).toEnd();

        assertEquals(start + 1, tailer.index());
        expected(tailer, "msg: Also hello world\n");
        assertEquals(start, tailer.index());
        expected(tailer, "msg: Hello world\n");
        assertEquals(start - 1, tailer.index());
*/

        queue.close();
        try {
            IOTools.shallowDeleteDirWithFiles(dir.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testWritingThreeMessages() throws FileNotFoundException {
        for (int m = 0; m <= 2; m++) {
            appendMode = m;

            File dir = new File(OS.TARGET + "/deleteme-" + System.nanoTime());
            dir.mkdir();

            SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(dir)
                    .blockSize(TEST_CHUNK_SIZE)
                    .indexCount(8)
                    .indexSpacing(1)
                    .build();

            long start = RollCycles.DAILY.toIndex(queue.cycle(), 0);
            appendMessage(queue, start, "Hello World");
            String expected1 = "--- !!meta-data #binary\n" +
                    "header: !SCQStore {\n" +
                    "  wireType: !WireType BINARY,\n" +
                    "  writePosition: 308,\n" +
                    "  roll: !SCQSRoll {\n" +
                    "    length: 86400000,\n" +
                    "    format: yyyyMMdd,\n" +
                    "    epoch: 0\n" +
                    "  },\n" +
                    "  indexing: !SCQSIndexing {\n" +
                    "    indexCount: 8,\n" +
                    "    indexSpacing: 1,\n" +
                    "    index2Index: 0,\n" +
                    "    lastIndex: 0\n" +
                    "  },\n" +
                    "  lastAcknowledgedIndexReplicated: 0\n" +
                    "}\n" +
                    "# position: 288\n" +
                    "--- !!data #binary\n" +
                    "msg: Hello World\n" +
                    "...\n" +
                    "# 5242568 bytes remaining\n";
            checkFileContents(dir.listFiles()[0], expected1);

            appendMessage(queue, start + 1, "Another Hello World");
            String expected2 = "--- !!meta-data #binary\n" +
                    "header: !SCQStore {\n" +
                    "  wireType: !WireType BINARY,\n" +
                    "  writePosition: 336,\n" +
                    "  roll: !SCQSRoll {\n" +
                    "    length: 86400000,\n" +
                    "    format: yyyyMMdd,\n" +
                    "    epoch: 0\n" +
                    "  },\n" +
                    "  indexing: !SCQSIndexing {\n" +
                    "    indexCount: 8,\n" +
                    "    indexSpacing: 1,\n" +
                    "    index2Index: 0,\n" +
                    "    lastIndex: 0\n" +
                    "  },\n" +
                    "  lastAcknowledgedIndexReplicated: 0\n" +
                    "}\n" +
                    "# position: 288\n" +
                    "--- !!data #binary\n" +
                    "msg: Hello World\n" +
                    "# position: 308\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World\n" +
                    "...\n" +
                    "# 5242540 bytes remaining\n";
            checkFileContents(dir.listFiles()[0], expected2);

            appendMessage(queue, start + 2, "Bye for now");

            String expected = "--- !!meta-data #binary\n" +
                    "header: !SCQStore {\n" +
                    "  wireType: !WireType BINARY,\n" +
                    "  writePosition: 356,\n" +
                    "  roll: !SCQSRoll {\n" +
                    "    length: 86400000,\n" +
                    "    format: yyyyMMdd,\n" +
                    "    epoch: 0\n" +
                    "  },\n" +
                    "  indexing: !SCQSIndexing {\n" +
                    "    indexCount: 8,\n" +
                    "    indexSpacing: 1,\n" +
                    "    index2Index: 0,\n" +
                    "    lastIndex: 0\n" +
                    "  },\n" +
                    "  lastAcknowledgedIndexReplicated: 0\n" +
                    "}\n" +
                    "# position: 288\n" +
                    "--- !!data #binary\n" +
                    "msg: Hello World\n" +
                    "# position: 308\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World\n" +
                    "# position: 336\n" +
                    "--- !!data #binary\n" +
                    "msg: Bye for now\n" +
                    "...\n" +
                    "# 5242520 bytes remaining\n";
            checkFileContents(dir.listFiles()[0], expected);
        }
    }

    public void checkFileContents(File file, String expected) throws FileNotFoundException {
        try (MappedBytes bytes = MappedBytes.mappedBytes(file, TEST_CHUNK_SIZE)) {
            bytes.readLimit(bytes.realCapacity());
            assertEquals(expected, Wires.fromSizePrefixedBlobs(bytes));
        }
    }

    @Test
    public void testWritingTwentyMessagesTinyIndex() throws FileNotFoundException, TimeoutException {
        for (int spacing : new int[]{1, 2, 4}) {
            File dir = new File(OS.TARGET + "/deleteme-" + System.nanoTime());
            dir.mkdir();

            SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(dir)
                    .blockSize(TEST_CHUNK_SIZE)
                    // only do this for testing
                    .indexCount(8)
                    .indexSpacing(spacing)
                    .build();


            long start = RollCycles.DAILY.toIndex(queue.cycle(), 0);
            ExcerptTailer tailer = queue.createTailer();
            assertFalse(tailer.moveToIndex(start));

            appendMessage(queue, start, "Hello World");
            String expected00 = "--- !!meta-data #binary\n" +
                    "header: !SCQStore {\n" +
                    "  wireType: !WireType BINARY,\n" +
                    "  writePosition: 308,\n" +
                    "  roll: !SCQSRoll {\n" +
                    "    length: 86400000,\n" +
                    "    format: yyyyMMdd,\n" +
                    "    epoch: 0\n" +
                    "  },\n" +
                    "  indexing: !SCQSIndexing {\n" +
                    "    indexCount: 8,\n" +
                    "    indexSpacing: 1,\n" +
                    "    index2Index: 0,\n" +
                    "    lastIndex: 0\n" +
                    "  },\n" +
                    "  lastAcknowledgedIndexReplicated: 0\n" +
                    "}\n" +
                    "# position: 288\n" +
                    "--- !!data #binary\n" +
                    "msg: Hello World\n" +
                    "...\n" +
                    "# 5242568 bytes remaining\n";
            checkFileContents(dir.listFiles()[0],
                    expected00.replace("indexSpacing: 1", "indexSpacing: " + spacing));

            assertTrue(tailer.moveToIndex(start));
            for (int i = 1; i < 19; i++) {
                assertFalse(tailer.moveToIndex(start + i));
                appendMessage(queue, start + i, "Another Hello World " + (i + 1));
                assertTrue(tailer.moveToIndex(start + i));
//                System.out.println(queue.dump());
            }
            assertFalse(tailer.moveToIndex(start + 19));
            appendMessage(queue, start + 19, "Bye for now");
            assertTrue(tailer.moveToIndex(start + 19));
            assertFalse(tailer.moveToIndex(start + 20));

            String expected1 = "--- !!meta-data #binary\n" +
                    "header: !SCQStore {\n" +
                    "  wireType: !WireType BINARY,\n" +
                    "  writePosition: 978,\n" +
                    "  roll: !SCQSRoll {\n" +
                    "    length: 86400000,\n" +
                    "    format: yyyyMMdd,\n" +
                    "    epoch: 0\n" +
                    "  },\n" +
                    "  indexing: !SCQSIndexing {\n" +
                    "    indexCount: 8,\n" +
                    "    indexSpacing: 1,\n" +
                    "    index2Index: 308,\n" +
                    "    lastIndex: 0\n" +
                    "  },\n" +
                    "  lastAcknowledgedIndexReplicated: 0\n" +
                    "}\n" +
                    "# position: 288\n" +
                    "--- !!data #binary\n" +
                    "msg: Hello World\n" +
                    "# position: 308\n" +
                    "--- !!meta-data #binary\n" +
                    "index2index: [\n" +
                    "  # length: 8, used: 0\n" +
                    "  0, 0, 0, 0, 0, 0, 0, 0\n" +
                    "]\n" +
                    "# position: 408\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 2\n" +
                    "# position: 438\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 3\n" +
                    "# position: 468\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 4\n" +
                    "# position: 498\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 5\n" +
                    "# position: 528\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 6\n" +
                    "# position: 558\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 7\n" +
                    "# position: 588\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 8\n" +
                    "# position: 618\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 9\n" +
                    "# position: 648\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 10\n" +
                    "# position: 679\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 11\n" +
                    "# position: 710\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 12\n" +
                    "# position: 741\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 13\n" +
                    "# position: 772\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 14\n" +
                    "# position: 803\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 15\n" +
                    "# position: 834\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 16\n" +
                    "# position: 865\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 17\n" +
                    "# position: 896\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 18\n" +
                    "# position: 927\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 19\n" +
                    "# position: 958\n" +
                    "--- !!data #binary\n" +
                    "msg: Bye for now\n" +
                    "...\n" +
                    "# 5241898 bytes remaining\n";
            String expected2 = "--- !!meta-data #binary\n" +
                    "header: !SCQStore {\n" +
                    "  wireType: !WireType BINARY,\n" +
                    "  writePosition: 978,\n" +
                    "  roll: !SCQSRoll {\n" +
                    "    length: 86400000,\n" +
                    "    format: yyyyMMdd,\n" +
                    "    epoch: 0\n" +
                    "  },\n" +
                    "  indexing: !SCQSIndexing {\n" +
                    "    indexCount: 8,\n" +
                    "    indexSpacing: 2,\n" +
                    "    index2Index: 308,\n" +
                    "    lastIndex: 0\n" +
                    "  },\n" +
                    "  lastAcknowledgedIndexReplicated: 0\n" +
                    "}\n" +
                    "# position: 288\n" +
                    "--- !!data #binary\n" +
                    "msg: Hello World\n" +
                    "# position: 308\n" +
                    "--- !!meta-data #binary\n" +
                    "index2index: [\n" +
                    "  # length: 8, used: 0\n" +
                    "  0, 0, 0, 0, 0, 0, 0, 0\n" +
                    "]\n" +
                    "# position: 408\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 2\n" +
                    "# position: 438\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 3\n" +
                    "# position: 468\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 4\n" +
                    "# position: 498\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 5\n" +
                    "# position: 528\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 6\n" +
                    "# position: 558\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 7\n" +
                    "# position: 588\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 8\n" +
                    "# position: 618\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 9\n" +
                    "# position: 648\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 10\n" +
                    "# position: 679\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 11\n" +
                    "# position: 710\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 12\n" +
                    "# position: 741\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 13\n" +
                    "# position: 772\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 14\n" +
                    "# position: 803\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 15\n" +
                    "# position: 834\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 16\n" +
                    "# position: 865\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 17\n" +
                    "# position: 896\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 18\n" +
                    "# position: 927\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 19\n" +
                    "# position: 958\n" +
                    "--- !!data #binary\n" +
                    "msg: Bye for now\n" +
                    "...\n" +
                    "# 5241898 bytes remaining\n";
            String expected4 = "--- !!meta-data #binary\n" +
                    "header: !SCQStore {\n" +
                    "  wireType: !WireType BINARY,\n" +
                    "  writePosition: 978,\n" +
                    "  roll: !SCQSRoll {\n" +
                    "    length: 86400000,\n" +
                    "    format: yyyyMMdd,\n" +
                    "    epoch: 0\n" +
                    "  },\n" +
                    "  indexing: !SCQSIndexing {\n" +
                    "    indexCount: 8,\n" +
                    "    indexSpacing: 4,\n" +
                    "    index2Index: 308,\n" +
                    "    lastIndex: 0\n" +
                    "  },\n" +
                    "  lastAcknowledgedIndexReplicated: 0\n" +
                    "}\n" +
                    "# position: 288\n" +
                    "--- !!data #binary\n" +
                    "msg: Hello World\n" +
                    "# position: 308\n" +
                    "--- !!meta-data #binary\n" +
                    "index2index: [\n" +
                    "  # length: 8, used: 0\n" +
                    "  0, 0, 0, 0, 0, 0, 0, 0\n" +
                    "]\n" +
                    "# position: 408\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 2\n" +
                    "# position: 438\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 3\n" +
                    "# position: 468\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 4\n" +
                    "# position: 498\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 5\n" +
                    "# position: 528\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 6\n" +
                    "# position: 558\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 7\n" +
                    "# position: 588\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 8\n" +
                    "# position: 618\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 9\n" +
                    "# position: 648\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 10\n" +
                    "# position: 679\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 11\n" +
                    "# position: 710\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 12\n" +
                    "# position: 741\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 13\n" +
                    "# position: 772\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 14\n" +
                    "# position: 803\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 15\n" +
                    "# position: 834\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 16\n" +
                    "# position: 865\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 17\n" +
                    "# position: 896\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 18\n" +
                    "# position: 927\n" +
                    "--- !!data #binary\n" +
                    "msg: Another Hello World 19\n" +
                    "# position: 958\n" +
                    "--- !!data #binary\n" +
                    "msg: Bye for now\n" +
                    "...\n" +
                    "# 5241898 bytes remaining\n";
            String expected = spacing == 1 ? expected1 :
                    spacing == 2 ? expected2 : expected4;
            checkFileContents(dir.listFiles()[0], expected);
        }
    }

    @Before
    public void threadDump() {
        threadDump = new ThreadDump();
    }

    @After
    public void checkThreadDump() {
        threadDump.assertNoNewThreads();
    }

    @Before
    public void resetAppendMode() {
        appendMode = 0;
    }

    public void appendMessage(SingleChronicleQueue queue, long expectedIndex, String msg) {
        ExcerptAppender appender = queue.createAppender();
        switch (appendMode) {
            case 1:
                appender.writeDocument(w -> w.write(() -> "msg").text(msg));
                break;

            case 2:
                Bytes bytes = Bytes.elasticByteBuffer();
                new BinaryWire(bytes).write(() -> "msg").text(msg);
                appender.writeBytes(bytes);
                break;

            default:
                try (DocumentContext dc = appender.writingDocument()) {
                    dc.wire().write(() -> "msg").text(msg);
                }
                break;
        }
        long index = appender.lastIndexAppended();
        assertHexEquals(expectedIndex, index);
    }

    public void readTwo(ExcerptTailer tailer) {
        long start = RollCycles.DAILY.toIndex(1, 0L);
        assertEquals(start, tailer.index());
        expected(tailer, "msg: Hello world\n");
        assertEquals(start + 1, tailer.index());
        expected(tailer, "msg: Also hello world\n");
        assertEquals(start + 2, tailer.index());
        try (DocumentContext dc = tailer.readingDocument()) {
            assertFalse(dc.isPresent());
        }
        assertEquals(start + 2, tailer.index());
    }

    @Test
    public void writeMap() {
        Map<String, Object> map = new TreeMap<>();
        map.put("abc", "def");
        map.put("hello", "world");
        map.put("number", 1L);
        map.put("double", 1.28);
        File dir = new File(OS.TARGET + "/deleteme-" + System.nanoTime());
        try (ChronicleQueue queue = SingleChronicleQueueBuilder.binary(dir).build()) {
            ExcerptAppender appender = queue.createAppender();
            appender.writeMap(map);

            map.put("abc", "aye-bee-see");
            appender.writeMap(map);

            assertEquals("--- !!meta-data #binary\n" +
                    "header: !SCQStore {\n" +
                    "  wireType: !WireType BINARY,\n" +
                    "  writePosition: 416,\n" +
                    "  roll: !SCQSRoll {\n" +
                    "    length: 86400000,\n" +
                    "    format: yyyyMMdd,\n" +
                    "    epoch: 0\n" +
                    "  },\n" +
                    "  indexing: !SCQSIndexing {\n" +
                    "    indexCount: !int 16384,\n" +
                    "    indexSpacing: 16,\n" +
                    "    index2Index: 0,\n" +
                    "    lastIndex: 0\n" +
                    "  },\n" +
                    "  lastAcknowledgedIndexReplicated: 0\n" +
                    "}\n" +
                    "# position: 288\n" +
                    "--- !!data #binary\n" +
                    "abc: def\n" +
                    "double: 1.28\n" +
                    "hello: world\n" +
                    "number: 1\n" +
                    "# position: 348\n" +
                    "--- !!data #binary\n" +
                    "abc: aye-bee-see\n" +
                    "double: 1.28\n" +
                    "hello: world\n" +
                    "number: 1\n" +
                    "...\n" +
                    "# 83885660 bytes remaining\n", queue.dump());

            ExcerptTailer tailer = queue.createTailer();
            Map<String, Object> map2 = tailer.readMap();
            Map<String, Object> map3 = tailer.readMap();
            assertEquals("{abc=def, double=1.28, hello=world, number=1}", map2.toString());
            assertEquals("{abc=aye-bee-see, double=1.28, hello=world, number=1}", map3.toString());
            assertNull(tailer.readMap());
        }
    }

    @Test
    public void writeMarshallable() {
        ClassAliasPool.CLASS_ALIASES.addAlias(Order.class);
        File dir = new File(OS.TARGET + "/deleteme-" + System.nanoTime());
        try (ChronicleQueue queue = SingleChronicleQueueBuilder.binary(dir).build()) {
            ExcerptAppender appender = queue.createAppender();
            appender.writeDocument(new Order("Symbol", Side.Buy, 1.2345, 1e6));
            appender.writeDocument(w -> w.write("newOrder").object(new Order("Symbol2", Side.Sell, 2.999, 10e6)));
            System.out.print(queue.dump());
        }
    }

    static class MyData extends AbstractMarshallable {
        String name;
        long num;
        double d;
        int counter;

        public MyData(String name, long num, double d, int counter) {
            this.name = name;
            this.num = num;
            this.d = d;
            this.counter = counter;
        }
    }
}
