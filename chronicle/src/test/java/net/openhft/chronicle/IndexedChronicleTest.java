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

package net.openhft.chronicle;

//import net.openhft.lang.affinity.PosixJNAAffinity;

import net.openhft.chronicle.tools.ChronicleIndexReader;
import net.openhft.chronicle.tools.ChronicleTools;
import net.openhft.lang.affinity.PosixJNAAffinity;
import org.junit.Test;

import java.io.IOException;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author peter.lawrey
 */
public class IndexedChronicleTest {
    public static final boolean WITH_BINDING;

    static {
        boolean binding = false;

        if (Runtime.getRuntime().availableProcessors() > 10) {
            try {
                PosixJNAAffinity.INSTANCE.getcpu();
                binding = true;
                System.out.println("binding: true");
            } catch (Throwable ignored) {
            }
        }
        WITH_BINDING = binding;
    }

    @Test
    public void singleThreaded() throws IOException {
        final String basePath = System.getProperty("java.io.tmpdir") + "/singleThreaded";
        ChronicleTools.deleteOnExit(basePath);

        ChronicleConfig config = ChronicleConfig.TEST.clone();
        config.dataBlockSize(4096);
        config.indexBlockSize(4096);
        IndexedChronicle chronicle = new IndexedChronicle(basePath, config);
        int i = 0;
        try {
            ExcerptAppender w = chronicle.createAppender();
            ExcerptTailer r = chronicle.createTailer();
            Random rand = new Random(1);
            // finish just at the end of the first page.
            for (i = 0; i < 5000; i++) {
//            System.out.println(i);

                int capacity = 16 * (1 + rand.nextInt(7));

                w.startExcerpt(capacity);
                assertEquals(0, w.position());
                w.writeLong(i);
                assertEquals(8, w.position());
                w.writeDouble(i);

                int expected = 16;
                assertEquals(expected, w.position());
                assertEquals(capacity - expected, w.remaining());

                w.finish();

//                ChronicleIndexReader.main(basePath + ".index");

                if (!r.nextIndex()) {
                    assertTrue(r.nextIndex());
                }
                if (expected != r.remaining())
                    assertEquals("index: " + r.index(), expected, r.remaining());
                if (expected != r.capacity())
                    assertEquals("index: " + r.index(), expected, r.capacity());
                assertEquals(0, r.position());
                long l = r.readLong();
                assertEquals(i, l);
                assertEquals(8, r.position());
                if (expected - 8 != r.remaining())
                    assertEquals("index: " + r.index(), expected - 8, r.remaining());
                double d = r.readDouble();
                assertEquals(i, d, 0.0);


                if (0 != r.remaining())
                    assertEquals("index: " + r.index(), 0, r.remaining());

                r.position(0);
                long l2 = r.readLong();
                assertEquals(i, l2);

                r.position(expected);

                r.finish();
/*
            double d = r.readDouble();
            assertEquals(2, d, 0.0);
            byte b = r.readByte();
            assertEquals(3, b);
*/
            }
            w.close();
            r.close();
        } finally {
            chronicle.close();
            System.out.println("i: " + i);
//            ChronicleIndexReader.main(basePath + ".index");
            ChronicleTools.deleteOnExit(basePath);
        }
    }

    @Test
    public void multiThreaded() throws IOException, InterruptedException {
        if (Runtime.getRuntime().availableProcessors() < 2) {
            System.err.println("Test requires 2 CPUs, skipping");
            return;
        }

        final String basePath = System.getProperty("java.io.tmpdir") + "/multiThreaded";
        ChronicleTools.deleteOnExit(basePath);
        final ChronicleConfig config = ChronicleConfig.DEFAULT.clone();
//        int dataBlockSize = 4 * 1024;
//        config.dataBlockSize(dataBlockSize);
//        config.indexBlockSize(dataBlockSize);
        IndexedChronicle chronicle = new IndexedChronicle(basePath, config);
        final ExcerptTailer r = chronicle.createTailer();

        final long words = 200L * 1000 * 1000;
        final int size = 4;
        long start = System.nanoTime();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (WITH_BINDING)
                        PosixJNAAffinity.INSTANCE.setAffinity(1L << 5);
                    IndexedChronicle chronicle = new IndexedChronicle(basePath, config);
                    final ExcerptAppender w = chronicle.createAppender();
                    for (int i = 0; i < words; i += size) {
                        w.startExcerpt(4L * size);
                        for (int s = 0; s < size; s++)
                            w.writeInt(1 + i);
//                        w.position(4L * size);
                        w.finish();
                    }
                    w.close();
//                    chronicle.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        t.start();

        if (WITH_BINDING)
            PosixJNAAffinity.INSTANCE.setAffinity(1L << 2);

        long maxDelay = 0, maxJitter = 0;
        for (long i = 0; i < words; i += size) {
            if (!r.nextIndex()) {
                long start0 = System.nanoTime();
                long last = start0;
                while (!r.nextIndex()) {
                    long now = System.nanoTime();
                    long jitter = now - last;
                    if (maxJitter < jitter)
                        maxJitter = jitter;
                    long delay0 = now - start0;
                    if (delay0 > 200e6)
                        throw new AssertionError("index: " + r.index());
                    if (maxDelay < delay0)
                        maxDelay = delay0;
                    last = now;
                }
            }
            try {
                for (int s = 0; s < size; s++) {
                    int j = r.readInt();
                    if (j != i + 1) {
                        ChronicleIndexReader.main(basePath + ".index");
                        throw new AssertionError(j + " != " + (i + 1));
                    }
                }
                r.finish();
            } catch (Exception e) {
                System.err.println("i= " + i);
                e.printStackTrace();
                break;
            }
        }

        r.close();
        long rate = words / size * 10 * 1000L / (System.nanoTime() - start);
        System.out.println("Rate = " + rate / 10.0 + " Mmsg/sec for " + size * 4 + " byte messages, maxJitter: " + maxJitter + " ns, maxDelay: " + maxDelay + " ns.");
        Thread.sleep(200);
        ChronicleTools.deleteOnExit(basePath);
    }

    @Test
    public void multiThreaded2() throws IOException, InterruptedException {
        if (Runtime.getRuntime().availableProcessors() < 3) {
            System.err.println("Test requires 3 CPUs, skipping");
            return;
        }
        final String basePath = System.getProperty("java.io.tmpdir") + "/multiThreaded";
        final String basePath2 = System.getProperty("java.io.tmpdir") + "/multiThreaded2";
        ChronicleTools.deleteOnExit(basePath);
        ChronicleTools.deleteOnExit(basePath2);

        final ChronicleConfig config = ChronicleConfig.DEFAULT.clone();
//        config.dataBlockSize(4*1024);
//        config.indexBlockSize(4 * 1024);

        final int runs = 100 * 1000 * 1000;
        final int size = 4;
        long start = System.nanoTime();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (WITH_BINDING)
                        PosixJNAAffinity.INSTANCE.setAffinity(1L << 5);
                    IndexedChronicle chronicle = new IndexedChronicle(basePath, config);
                    final ExcerptAppender w = chronicle.createAppender();
                    for (int i = 0; i < runs; i += size) {
                        w.startExcerpt(4 * size);
                        for (int s = 0; s < size; s++)
                            w.writeInt(1 + i);
                        w.finish();
                    }
                    w.close();
//                    chronicle.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }, "t1");
        t.start();

        Thread t2 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (WITH_BINDING)
                        PosixJNAAffinity.INSTANCE.setAffinity(1L << 2);
                    IndexedChronicle chronicle = new IndexedChronicle(basePath, config);
                    final ExcerptTailer r = chronicle.createTailer();
                    IndexedChronicle chronicle2 = new IndexedChronicle(basePath2, config);
                    final ExcerptAppender w = chronicle2.createAppender();
                    for (int i = 0; i < runs; i += size) {
                        do {
                        } while (!r.nextIndex());

                        w.startExcerpt(r.remaining());
                        for (int s = 0; s < size; s++)
                            w.writeInt(r.readInt());
                        r.finish();
                        w.finish();
                    }
                    w.close();
//                    chronicle.close();
//                    chronicle2.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }, "t2");
        t2.start();

        IndexedChronicle chronicle = new IndexedChronicle(basePath2, config);
        final ExcerptTailer r = chronicle.createTailer();

        for (int i = 0; i < runs; i += size) {
            do {
            } while (!r.nextIndex());
            try {
                for (int s = 0; s < size; s++) {
                    long l = r.readInt();
                    if (l != i + 1)
                        throw new AssertionError();
                }
                r.finish();
            } catch (Exception e) {
                System.err.println("i= " + i);
                e.printStackTrace();
                break;
            }
        }
        r.close();
        long rate = 2 * runs / size * 10000L / (System.nanoTime() - start);
        System.out.println("Rate = " + rate / 10.0 + " Mmsg/sec");
        chronicle.close();
        Thread.sleep(200);
        ChronicleTools.deleteOnExit(basePath);
        ChronicleTools.deleteOnExit(basePath2);
    }
}
