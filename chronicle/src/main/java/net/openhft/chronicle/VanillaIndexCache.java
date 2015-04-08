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

package net.openhft.chronicle;

import net.openhft.lang.io.VanillaMappedBytes;
import net.openhft.lang.io.VanillaMappedCache;
import net.openhft.lang.model.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;

public class VanillaIndexCache implements Closeable {
    public static final Logger LOGGER = LoggerFactory.getLogger(VanillaIndexCache.class);
    public static final String FILE_NAME_PREFIX = "index-";

    private final String basePath;
    private final File baseFile;
    private final IndexKey key = new IndexKey();
    private final int blockBits;
    private final VanillaDateCache dateCache;
    private final VanillaMappedCache<IndexKey> cache;
    private final int[][] appenderCycles;

    VanillaIndexCache(
            @NotNull ChronicleQueueBuilder.VanillaChronicleQueueBuilder builder,
            @NotNull VanillaDateCache dateCache,
            int blocksBits) {

        this.baseFile = builder.path();
        this.basePath = this.baseFile.getAbsolutePath();

        this.blockBits = blocksBits;
        this.dateCache = dateCache;

        this.cache = new VanillaMappedCache<>(
            builder.indexCacheCapacity(),
            true,
            builder.cleanupOnClose()
        );

        int lastCycle = (int)lastCycle();
        int lastIndex = lastIndexFile(lastCycle);
        this.appenderCycles = new int[][]{
            new int[]{ lastCycle, lastIndex },
            new int[]{ 0, 0 }
        };
    }

    public static long append(final VanillaMappedBytes bytes, final long indexValue, final boolean synchronous) {
        // Position can be changed by another thread, so take a snapshot each loop so that
        // buffer overflows are not generated when advancing to the next position.
        // As a result, the position could step backwards when this method is called concurrently,
        // but the compareAndSwapLong call ensures that data is never overwritten.

        if (bytes != null) {
            boolean endOfFile = false;
            long position = bytes.position();
            while (!endOfFile) {
                endOfFile = (bytes.limit() - position) < 8;
                if (!endOfFile) {
                    if (bytes.compareAndSwapLong(position, 0L, indexValue)) {
                        bytes.lazyPosition(position + 8);
                        if (synchronous) {
                            bytes.force();
                        }

                        return position;
                    }
                }
                position += 8;
            }
        }

        return -1;
    }

    public static long countIndices(final VanillaMappedBytes buffer) {
        long indices = 0;
        for (long offset = 0; offset < buffer.capacity(); offset += 8) {
            if (buffer.readLong(offset) != 0) {
                indices++;
            } else {
                break;
            }
        }

        return indices;
    }

    public File fileFor(int cycle, int indexCount, boolean forAppend) throws IOException {
        return new File(
            new File(basePath, dateCache.formatFor(cycle)),
            FILE_NAME_PREFIX + indexCount);
    }

    public synchronized VanillaMappedBytes indexFor(int cycle, int indexCount, boolean forAppend) throws IOException {
        key.cycle = cycle;
        key.indexCount = indexCount;

        VanillaMappedBytes vmb = this.cache.get(key);
        if(vmb == null) {
            vmb = this.cache.put(
                key.clone(),
                VanillaChronicleUtils.mkFiles(
                    basePath,
                    dateCache.formatFor(cycle),
                    FILE_NAME_PREFIX + indexCount,
                    forAppend),
                1L << blockBits,
                indexCount);
        }

        vmb.reserve();

        return vmb;
    }

    @Override
    public synchronized void close() {
        this.cache.close();
    }

    int lastIndexFile() {
        int lastCycle = (int)lastCycle();
        return lastIndexFile(lastCycle);
    }

    int lastIndexFile(int cycle) {
        return lastIndexFile(cycle, 0);
    }

    int lastIndexFile(int cycle, int defaultCycle) {
        int maxIndex = -1;

        final File cyclePath = new File(baseFile, dateCache.formatFor(cycle));
        final File[] files = cyclePath.listFiles();
        if (files != null) {
            for (final File file : files) {
                String name = file.getName();
                if (name.startsWith(FILE_NAME_PREFIX)) {
                    int index = Integer.parseInt(name.substring(FILE_NAME_PREFIX.length()));
                    if (maxIndex < index) {
                        maxIndex = index;
                    }
                }
            }
        }

        return maxIndex != -1 ? maxIndex : defaultCycle;
    }

    public synchronized VanillaMappedBytes append(
            int cycle, long indexValue, boolean synchronous, long[] position) throws IOException {

        int localIndex = this.appenderCycles[0][1];
        int indexToUpdate = 0;

        if(this.appenderCycles[0][0] < cycle) {
            // New cycle detected, swap references
            this.appenderCycles[1][0] = this.appenderCycles[0][0];
            this.appenderCycles[1][1] = this.appenderCycles[0][1];
            this.appenderCycles[0][0] = cycle;
            this.appenderCycles[0][1] = 0;

            localIndex = 0;
        } else if(this.appenderCycles[0][0] > cycle) {
            if(this.appenderCycles[1][0] == cycle) {
                // Old cycle detected
                localIndex = this.appenderCycles[1][1];
                indexToUpdate = 1;
            } else {
                // un-tracked cycle, search for last index file
                localIndex = lastIndexFile(cycle, 0);
                indexToUpdate = -1;
            }
        }

        for (int indexCount = localIndex; indexCount < 10000; indexCount++) {
            VanillaMappedBytes vmb = indexFor(cycle, indexCount, true);
            long position0 = append(vmb, indexValue, synchronous);
            if (position0 >= 0) {
                position[0] = position0;
                if(indexToUpdate == 0 || indexToUpdate == 1) {
                    this.appenderCycles[indexToUpdate][1] = indexCount;
                }

                return vmb;
            }

            vmb.release();
        }

        throw new AssertionError(
            "Unable to write index" + indexValue + "on cycle " + cycle + "(" + dateCache.formatFor(cycle) + ")"
        );
    }

    public long firstCycle() {
        File[] files = baseFile.listFiles();
        if (files == null) {
            return -1;
        }

        long firstDate = Long.MAX_VALUE;
        for (File file : files) {
            try {
                long date = dateCache.parseCount(file.getName());
                if (firstDate > date) {
                    firstDate = date;
                }
            } catch (ParseException ignored) {
                // ignored
            }
        }

        return firstDate;
    }

    public long lastCycle() {
        final File[] files = baseFile.listFiles();
        if (files == null) {
            return -1;
        }

        long firstDate = Long.MIN_VALUE;
        for (File file : files) {
            try {
                long date = dateCache.parseCount(file.getName());
                if (firstDate < date) {
                    firstDate = date;
                }
            } catch (ParseException ignored) {
                // ignored
            }
        }

        return firstDate;
    }

    public void checkCounts(int min, int max) {
        this.cache.checkCounts(min,max);
    }

    static class IndexKey implements Cloneable {
        int cycle;
        int indexCount;

        @Override
        public int hashCode() {
            return cycle * 10191 ^ indexCount;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof IndexKey)) {
                return false;
            }

            IndexKey key = (IndexKey) obj;
            return indexCount == key.indexCount && cycle == key.cycle;
        }

        @Override
        protected IndexKey clone() {
            try {
                return (IndexKey) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public String toString() {
            return "IndexKey ["
                + "cycle="       + cycle      + ","
                + "indexCount="  + indexCount + "]";
        }
    }
}
