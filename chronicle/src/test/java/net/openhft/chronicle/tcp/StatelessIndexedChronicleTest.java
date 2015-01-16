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

import net.openhft.chronicle.Chronicle;
import net.openhft.chronicle.ChronicleQueueBuilder;
import net.openhft.chronicle.ExcerptAppender;
import net.openhft.chronicle.ExcerptTailer;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static junit.framework.TestCase.assertTrue;
import static net.openhft.chronicle.ChronicleQueueBuilder.remoteTailer;
import static net.openhft.chronicle.ChronicleQueueBuilder.indexed;
import static net.openhft.chronicle.ChronicleQueueBuilder.ReplicaChronicleQueueBuilder;
import static org.junit.Assert.*;

public class StatelessIndexedChronicleTest extends StatelessChronicleTestBase {

    @Test
    public void testIndexedStatelessSink_001() throws Exception {
        final String basePathSource = getIndexedTestPath("-source");
        final PortSupplier portSupplier = new PortSupplier();

        final Chronicle source = ChronicleQueueBuilder.indexed(basePathSource)
            .source()
                .bindAddress(0)
                .connectionListener(portSupplier)
            .build();

        final Chronicle sink = ChronicleQueueBuilder.remoteTailer()
            .connectAddress("localhost", portSupplier.getAndCheckPort())
            .build();

        final int items = 1000000;
        final ExcerptAppender appender = source.createAppender();

        try {
            for (long i = 1; i <= items; i++) {
                appender.startExcerpt(8);
                appender.writeLong(i);
                appender.finish();
            }

            appender.close();

            final ExcerptTailer tailer1 = sink.createTailer().toStart();
            assertEquals(-1,tailer1.index());

            for (long i = 1; i <= items; i++) {
                assertTrue(tailer1.nextIndex());
                assertEquals(i - 1, tailer1.index());
                assertEquals(i, tailer1.readLong());
                tailer1.finish();
            }

            assertFalse(tailer1.nextIndex());
            tailer1.close();

            final ExcerptTailer tailer2 = sink.createTailer().toEnd();
            assertEquals(items - 1, tailer2.index());
            assertEquals(items, tailer2.readLong());
            assertFalse(tailer2.nextIndex());
            tailer2.close();

            sink.close();
            sink.clear();
        } finally {
            source.close();
            source.clear();
        }
    }

    @Test
    public void testIndexedStatelessSink_002() throws Exception {
        final String basePathSource = getIndexedTestPath("-source");
        final PortSupplier portSupplier = new PortSupplier();

        final Chronicle source = ChronicleQueueBuilder.indexed(basePathSource)
            .source()
                .bindAddress(0)
                .connectionListener(portSupplier)
            .build();

        final int port = portSupplier.getAndCheckPort();
        final Chronicle sink = ChronicleQueueBuilder.remoteTailer()
            .connectAddress("localhost", port)
            .build();

        try {
            final ExcerptAppender appender = source.createAppender();
            appender.startExcerpt(8);
            appender.writeLong(1);
            appender.finish();
            appender.startExcerpt(8);
            appender.writeLong(2);
            appender.finish();

            final ExcerptTailer tailer = sink.createTailer().toEnd();
            assertFalse(tailer.nextIndex());

            appender.startExcerpt(8);
            appender.writeLong(3);
            appender.finish();

            while(!tailer.nextIndex());

            assertEquals(2, tailer.index());
            assertEquals(3, tailer.readLong());
            tailer.finish();
            tailer.close();

            appender.close();

            sink.close();
            sink.clear();
        } finally {
            source.close();
            source.clear();
        }
    }

    @Ignore // Sometimes it fails or stales
    @Test
    public void testIndexedStatelessSink_003() throws Exception {
        final String basePathSource = getIndexedTestPath("-source");
        final PortSupplier portSupplier = new PortSupplier();

        final Chronicle source = ChronicleQueueBuilder.indexed(basePathSource)
            .source()
                .bindAddress(0)
                .connectionListener(portSupplier)
            .build();

        final int port = portSupplier.getAndCheckPort();
        final Chronicle sink = ChronicleQueueBuilder.remoteTailer()
            .connectAddress("localhost", port)
            .build();

        try {
            final int items = 1000000;
            final ExcerptAppender appender = source.createAppender();
            final ExcerptTailer tailer = sink.createTailer();
            final Random r = new Random();

            for (long i = 0; i <= items; i++) {
                appender.startExcerpt(8);
                appender.writeLong(i);
                appender.finish();
            }

            appender.close();

            for (int i=1; i < 100; i++) {
                int index = r.nextInt(items);

                assertTrue("Index " + index + " not found", tailer.index(index));
                assertEquals(index, tailer.index());
                assertEquals(index, tailer.readLong());

                tailer.finish();
            }

            tailer.close();

            sink.close();
            sink.clear();
        } finally {
            source.close();
            source.clear();
        }
    }

    @Test
    public void testIndexedStatelessSink_004() throws Exception {
        final int tailers = 4;
        final int items = 1000000;

        final String basePathSource = getIndexedTestPath("-source");
        final ExecutorService executor = Executors.newFixedThreadPool(tailers);

        final PortSupplier portSupplier = new PortSupplier();
        final Chronicle source = ChronicleQueueBuilder.indexed(basePathSource)
            .source()
                .bindAddress(0)
                .connectionListener(portSupplier)
            .build();

        final int port = portSupplier.getAndCheckPort();

        try {
            for(int i=0;i<tailers;i++) {
                executor.submit(new Runnable() {
                    public void run() {
                        try {
                            final Chronicle sink = ChronicleQueueBuilder.remoteTailer()
                                .connectAddress("localhost", port)
                                .build();

                            final ExcerptTailer tailer = sink.createTailer().toStart();
                            for (long i = 0; i < items; ) {
                                if (tailer.nextIndex()) {
                                    assertEquals(i, tailer.index());
                                    assertEquals(i, tailer.readLong());

                                    tailer.finish();

                                    i++;
                                }
                            }

                            tailer.close();

                            sink.close();
                            sink.clear();
                        } catch (Exception e) {
                            errorCollector.addError(e);
                        } catch (AssertionError e) {
                            errorCollector.addError(e);
                        }
                    }
                });
            }

            Thread.sleep(100);

            final ExcerptAppender appender = source.createAppender();
            for (int i=0; i<items; i++) {
                appender.startExcerpt(8);
                appender.writeLong(i);
                appender.finish();
            }

            appender.close();

            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } finally {
            source.close();
            source.clear();
        }
    }

    @Test
    public void testIndexedStatelessSink_005() throws Exception {
        final String basePathSource = getIndexedTestPath("-source");
        final PortSupplier portSupplier = new PortSupplier();

        final Chronicle source = ChronicleQueueBuilder.indexed(basePathSource)
            .source()
                .bindAddress(0)
                .connectionListener(portSupplier)
            .build();

        final int port = portSupplier.getAndCheckPort();
        final Chronicle sink = ChronicleQueueBuilder.remoteTailer()
            .connectAddress("localhost", port)
            .build();

        final int items = 1000;
        final ExcerptAppender appender = source.createAppender();
        final ExcerptTailer tailer = sink.createTailer();

        try {
            for (int i = 0; i < items; i++) {
                appender.startExcerpt(8);
                appender.writeLong(i);
                appender.finish();

                assertTrue(tailer.index(i));
                assertEquals(i, tailer.readLong());
                tailer.finish();
            }

            appender.close();
            tailer.close();

            sink.close();
            sink.clear();
        } finally {
            source.close();
            source.clear();
        }
    }

    @Test
    public void testIndexedStatelessSink_006() throws Exception {
        final String basePathSource = getIndexedTestPath("-source");
        final PortSupplier portSupplier = new PortSupplier();

        final Chronicle source = ChronicleQueueBuilder.indexed(basePathSource)
            .source()
                .bindAddress(0)
                .connectionListener(portSupplier)
            .build();

        final int port = portSupplier.getAndCheckPort();
        final Chronicle sink = ChronicleQueueBuilder.remoteTailer()
            .connectAddress("localhost", port)
            .build();

        final int items = 1000000;
        final ExcerptAppender appender = source.createAppender();

        try {
            for (int i=1; i <= items; i++) {
                appender.startExcerpt(8);
                appender.writeLong(i);
                appender.finish();
            }

            appender.close();

            final ExcerptTailer tailer1 = sink.createTailer().toStart();
            assertEquals(-1,tailer1.index());
            assertTrue(tailer1.nextIndex());
            assertEquals(0, tailer1.index());
            assertEquals(1, tailer1.readLong());
            tailer1.finish();
            tailer1.close();

            final ExcerptTailer tailer2 = sink.createTailer().toEnd();
            assertEquals(items - 1, tailer2.index());
            assertEquals(items, tailer2.readLong());
            tailer2.finish();
            tailer2.close();

            sink.close();
            sink.clear();
        } finally {
            source.close();
            source.clear();
        }
    }

    // *************************************************************************
    //
    // *************************************************************************

    @Test
    public void testStatelessIndexedNonBlockingClient() throws Exception {
        final String basePathSource = getIndexedTestPath("-source");
        final PortSupplier portSupplier = new PortSupplier();
        final int messages = 1000000;

        final Chronicle source = indexed(basePathSource)
                .source()
                .bindAddress(0)
                .connectionListener(portSupplier)
                .build();

        final ReplicaChronicleQueueBuilder builder = remoteTailer()
                .connectAddress("localhost", portSupplier.getAndCheckPort())
                .readSpinCount(5);

        final Chronicle sink = builder.build();
        final ExcerptTailer tailer = sink.createTailer();

        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final ExcerptAppender appender = source.createAppender();
                    for (int i = 1; i <= messages; i++) {
                        // use a size which will cause mis-alignment.
                        appender.startExcerpt();
                        appender.writeLong(i);
                        appender.append(' ');
                        appender.append(i);
                        appender.append('\n');
                        appender.finish();
                    }

                    appender.close();
                    LOGGER.info("Finished writing messages");

                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        });

        t.start();

        long start = 0;
        long end = 0;
        boolean hasNext = false;

        for(int i=1; i<=messages; ) {
            start   = System.currentTimeMillis();
            hasNext = tailer.nextIndex();
            end     = System.currentTimeMillis();

            assertTrue("Timeout exceeded " + (end - start), end - start < builder.heartbeatIntervalMillis());

            if(hasNext) {
                assertEquals(i, tailer.readInt());
                i++;
            }

            tailer.finish();
        }

        tailer.close();

        t.join();

        source.close();
        source.clear();
        sink.close();
        sink.clear();
    }

    // *************************************************************************
    // JIRA
    // *************************************************************************

    /*
     * https://higherfrequencytrading.atlassian.net/browse/CHRON-74
     */
    @Test
    public void testIndexedJiraChron74() throws Exception {
        final String basePathSource = getIndexedTestPath("-source");
        final PortSupplier portSupplier = new PortSupplier();

        final Chronicle chronicle = ChronicleQueueBuilder.indexed(basePathSource)
            .source()
                .bindAddress(0)
                .connectionListener(portSupplier)
            .build();

        testJiraChron74(portSupplier.getAndCheckPort(), chronicle);
    }

    /*
     * https://higherfrequencytrading.atlassian.net/browse/CHRON-75
     */
    @Test
    public void testIndexedJiraChron75() throws Exception {
        final String basePathSource = getIndexedTestPath("-source");
        final PortSupplier portSupplier = new PortSupplier();

        final Chronicle chronicle = ChronicleQueueBuilder.indexed(basePathSource)
            .source()
                .bindAddress(0)
                .connectionListener(portSupplier)
            .build();

        testJiraChron75(portSupplier.getAndCheckPort(), chronicle);
    }

    /*
     * https://higherfrequencytrading.atlassian.net/browse/CHRON-78
     */
    @Test
    public void testIndexedJiraChron78() throws Exception {
        final String basePathSource = getIndexedTestPath("-source");
        final PortSupplier portSupplier = new PortSupplier();

        final Chronicle chronicle = ChronicleQueueBuilder.indexed(basePathSource)
            .source()
                .bindAddress(0)
                .connectionListener(portSupplier)
            .build();

        testJiraChron78(portSupplier.getAndCheckPort(), chronicle);
    }

    /*
     * https://higherfrequencytrading.atlassian.net/browse/CHRON-81
     */
    @Test
    public void testIndexedJiraChron81() throws Exception {
        final String basePathSource = getIndexedTestPath("-source");
        final PortSupplier portSupplier = new PortSupplier();

        final Chronicle chronicle = ChronicleQueueBuilder.indexed(basePathSource)
            .source()
                .bindAddress(0)
                .connectionListener(portSupplier)
            .build();

        testJiraChron81(portSupplier.getAndCheckPort(), chronicle);
    }
}
