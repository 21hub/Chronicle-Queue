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

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.queue.*;
import net.openhft.chronicle.wire.WireType;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class SingleChronicleQueueTest extends ChronicleQueueTestBase {

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {WireType.TEXT},
                {WireType.BINARY}
        });
    }

    private final WireType wireType;

    /**
     * @param wireType
     */
    public SingleChronicleQueueTest(WireType wireType) {
        this.wireType = wireType;
    }

    // *************************************************************************
    //
    // TESTS
    //
    // *************************************************************************

    @Test
    public void testAppend() throws IOException {
        final ChronicleQueue queue = new SingleChronicleQueueBuilder(getTmpDir())
                .wireType(this.wireType)
                .build();

        final ExcerptAppender appender = queue.createAppender();
        for (int i = 0; i < 10; i++) {
            final int n = i;
            assertEquals(n, appender.writeDocument(w -> w.write(TestKey.test).int32(n)));
        }
    }

    @Test
    public void testAppendAndRead() throws IOException {
        final ChronicleQueue queue = new SingleChronicleQueueBuilder(getTmpDir())
                .wireType(this.wireType)
                .build();

        final ExcerptAppender appender = queue.createAppender();
        for (int i = 0; i < 10; i++) {
            final int n = i;
            assertEquals(n, appender.writeDocument(w -> w.write(TestKey.test).int32(n)));
            assertEquals(n, appender.index());
        }

        final ExcerptTailer tailer = queue.createTailer();

        // Sequential read
        for (int i = 0; i < 10; i++) {
            final int n = i;
            assertTrue(tailer.readDocument(r -> assertEquals(n, r.read(TestKey.test).int32())));
            assertEquals(n, tailer.index());
        }

        // Random read
        for (int i = 0; i < 10; i++) {
            final int n = i;
            assertTrue(tailer.index(n));
            assertTrue(tailer.readDocument(r -> assertEquals(n, r.read(TestKey.test).int32())));
            assertEquals(n, tailer.index());
        }
    }

    @Test
    public void testReadAndAppend() throws IOException {
        final ChronicleQueue queue = new SingleChronicleQueueBuilder(getTmpDir())
                .wireType(this.wireType)
                .build();

        int[] results = new int[2];

        Thread t = new Thread(() -> {
            try {
                final ExcerptTailer tailer = queue.createTailer();
                for (int i = 0; i < 2; ) {
                    boolean read = tailer.readDocument(r -> {
                        int result = r.read(TestKey.test).int32();
                        results[result] = result;
                    });

                    if (read) {
                        i++;
                    } else {
                        // Pause for a little
                        Jvm.pause(10);
                    }
                }
            } catch (Exception e) {
                assertTrue(false);
            }
        });
        t.setDaemon(true);
        t.start();

        //Give the tailer thread enough time to initialise before send
        //the messages
        Jvm.pause(1000);

        final ExcerptAppender appender = queue.createAppender();
        for (int i = 0; i < 2; i++) {
            final int n = i;
            appender.writeDocument(w -> w.write(TestKey.test).int32(n));
        }

        Jvm.pause(1000);

        assertArrayEquals(new int[]{0, 1}, results);
    }

    @Test
    public void testAppendAndReadWithRolling() throws IOException {

        final ChronicleQueue queue = new SingleChronicleQueueBuilder(getTmpDir())
                .wireType(this.wireType)
                .rollCycle(RollCycles.SECONDS)
                .build();

        final ExcerptAppender appender = queue.createAppender();
        for (int i = 0; i < 20; i++) {
            final int n = i;
            Jvm.pause(500);
            appender.writeDocument(w -> w.write(TestKey.test).int32(n));
        }

        final ExcerptTailer tailer = queue.createTailer().toStart();
        for (int i = 0; i < 20; i++) {
            final int n = i;
            assertTrue(tailer.readDocument(r -> assertEquals(n, r.read(TestKey.test).int32())));
        }
    }

    @Test
    public void testAppendAndReadWithRolling2() throws IOException {
        final File dir = getTmpDir();

        for (int i = 0; i < 10; i++) {
            final int n = i;

            new SingleChronicleQueueBuilder(dir)
                    .wireType(this.wireType)
                    .rollCycle(RollCycles.SECONDS)
                    .build()
                    .createAppender().writeDocument(w -> w.write(TestKey.test).int32(n));

            Jvm.pause(500);
        }

        final ChronicleQueue queue = new SingleChronicleQueueBuilder(dir)
                .wireType(this.wireType)
                .rollCycle(RollCycles.SECONDS)
                .build();

        final ExcerptTailer tailer = queue.createTailer().toStart();
        for (int i = 0; i < 10; i++) {
            final int n = i;
            assertTrue(tailer.readDocument(r -> assertEquals(n, r.read(TestKey.test).int32())));
        }
    }

    /// @Ignore("todo rob to fix")
    @Test
    public void testAppendAndReadAtIndex() throws IOException {
        final ChronicleQueue queue = new SingleChronicleQueueBuilder(getTmpDir())
                .wireType(this.wireType)
                .build();

        final ExcerptAppender appender = queue.createAppender();
        for (int i = 0; i < 5; i++) {
            final int n = i;
            assertEquals(n, appender.writeDocument(w -> w.write(TestKey.test).int32(n)));
            assertEquals(n, appender.index());
        }

        final ExcerptTailer tailer = queue.createTailer();
        for (int i = 0; i < 5; i++) {
            assertTrue(tailer.index(i));

            final int n = i;
            assertTrue(tailer.readDocument(r -> assertEquals(n, r.read(TestKey.test).int32())));
            assertEquals(n, tailer.index());
        }
    }

    @Test
    public void testSimpleWire() throws Exception {

        File file = File.createTempFile("chronicle.", "q");
        file.deleteOnExit();

        try {

            final ChronicleQueue chronicle = new SingleChronicleQueueBuilder(getTmpDir())
                    .wireType(this.wireType)
                    .build();

            final ExcerptAppender appender = chronicle.createAppender();
            appender.writeDocument(wire -> wire.write(() -> "FirstName").text("Steve"));
            appender.writeDocument(wire -> wire.write(() -> "Surname").text("Jobs"));

            StringBuilder first = new StringBuilder();
            StringBuilder surname = new StringBuilder();

            final ExcerptTailer tailer = chronicle.createTailer();

            tailer.readDocument(wire -> wire.read(() -> "FirstName").text(first));
            tailer.readDocument(wire -> wire.read(() -> "Surname").text(surname));

            Assert.assertEquals("Steve Jobs", first + " " + surname);


        } finally {
            file.delete();
        }

    }


    @Test
    public void testReadAtIndex() throws Exception {

        final File file = File.createTempFile("chronicle.", "q");
        file.deleteOnExit();

        try {

            final ChronicleQueue chronicle = new SingleChronicleQueueBuilder(getTmpDir())
                    .wireType(this.wireType)
                    .build();
            final ExcerptAppender appender = chronicle.createAppender();

            // create 100 documents
            for (int i = 0; i < 100; i++) {
                final int j = i;
                appender.writeDocument(wire -> wire.write(() -> "key").text("value=" + j));
            }

            final ExcerptTailer tailer = chronicle.createTailer();


            //   QueueDumpMain.dump(file, new PrintWriter(System.out));

            StringBuilder sb = new StringBuilder();

            for (int i : new int[]{5, 63, 64, 65}) {
                tailer.index(i);
                tailer.readDocument(wire -> wire.read(() -> "key").text(sb));
                Assert.assertEquals("value=" + i, sb.toString());
            }

        } finally {
            file.delete();
        }

    }

    @Test
    public void testLastWrittenIndexPerAppender() throws Exception {

        File file = File.createTempFile("chronicle.", "q");
        file.deleteOnExit();
        try {

            final ChronicleQueue chronicle = new SingleChronicleQueueBuilder(getTmpDir())
                    .wireType(this.wireType)
                    .build();
            final ExcerptAppender appender = chronicle.createAppender();

            appender.writeDocument(wire -> wire.write(() -> "key").text("test"));
            Assert.assertEquals(0, appender.index());

        } finally {
            file.delete();
        }

    }

    @Test(expected = IllegalStateException.class)
    public void testLastWrittenIndexPerAppenderNoData() throws Exception {

        File file = File.createTempFile("chronicle.", "q");
        file.deleteOnExit();
        try {
            final ChronicleQueue chronicle = new SingleChronicleQueueBuilder(getTmpDir())
                    .wireType(this.wireType)
                    .build();
            final ExcerptAppender appender = chronicle.createAppender();
            appender.index();
            Assert.fail();
        } finally {
            file.delete();
        }

    }


    @Ignore
    @Test
    public void testLastIndexPerChronicle() throws Exception {

        File file = File.createTempFile("chronicle.", "q");
        file.deleteOnExit();
        try {

            final ChronicleQueue chronicle = new SingleChronicleQueueBuilder(getTmpDir())
                    .wireType(this.wireType)
                    .build();

            final ExcerptAppender appender = chronicle.createAppender();

            appender.writeDocument(wire -> wire.write(() -> "key").text("test"));
            Assert.assertEquals(0, chronicle.lastWrittenIndex());

        } finally {
            file.delete();
        }

    }


    @Test
    public void testHeaderIndexReadAtIndex() throws Exception {

        File file = File.createTempFile("chronicle.", "q");
        file.deleteOnExit();
        try {

            final ChronicleQueue chronicle = new SingleChronicleQueueBuilder(getTmpDir())
                    .wireType(this.wireType)
                    .build();

            final ExcerptAppender appender = chronicle.createAppender();

            // create 100 documents
            for (int i = 0; i < 100; i++) {
                final int j = i;
                appender.writeDocument(wire -> wire.write(() -> "key").text("value=" + j));
            }

            final ExcerptTailer tailer = chronicle.createTailer();
            tailer.index(5);

            StringBuilder sb = new StringBuilder();
            tailer.readDocument(wire -> wire.read(() -> "key").text(sb));

            Assert.assertEquals("value=5", sb.toString());

        } finally {
            file.delete();
        }

    }


}
