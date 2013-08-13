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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

/**
 * @author peter.lawrey
 */
public class IndexedChronicle implements Chronicle {
    final FileChannel indexFile;
    final FileChannel dataFile;
    final ChronicleConfig config;
    private long size = 0;

    public IndexedChronicle(String basePath) throws FileNotFoundException {
        this(basePath, ChronicleConfig.DEFAULT);
    }

    public IndexedChronicle(String basePath, ChronicleConfig config) throws FileNotFoundException {
        this.config = config.clone();
        File parentFile = new File(basePath).getParentFile();
        if (parentFile != null)
            parentFile.mkdirs();
        this.indexFile = new RandomAccessFile(basePath + ".index", "rw").getChannel();
        this.dataFile = new RandomAccessFile(basePath + ".data", "rw").getChannel();
    }

    @Override
    public void close() throws IOException {
        this.indexFile.close();
        this.dataFile.close();
    }

    @Override
    public ExcerptReader createReader() {
        return new NativeExcerptTailer(this);
    }

    @Override
    public ExcerptTailer createTailer() {
        return new NativeExcerptTailer(this);
    }

    @Override
    public ExcerptAppender createAppender() {
        return new NativeExcerptAppender(this);
    }

    @Override
    public long size() {
        return size;
    }

    void incrSize() {
        size++;
    }
}
