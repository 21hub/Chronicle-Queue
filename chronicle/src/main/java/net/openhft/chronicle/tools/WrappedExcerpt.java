/*
 * Copyright 2013 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.tools;

import net.openhft.chronicle.*;
import net.openhft.lang.io.ByteStringAppender;
import net.openhft.lang.io.BytesMarshallerFactory;
import net.openhft.lang.io.MutableDecimal;
import net.openhft.lang.io.StopCharTester;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * @author peter.lawrey
 */
public class WrappedExcerpt implements ExcerptTailer, ExcerptAppender, Excerpt {
    private final ExcerptTailer tailer;
    private final ExcerptAppender appender;
    private final ExcerptCommon common;
    private final Excerpt excerpt;

    public WrappedExcerpt(ExcerptCommon excerptCommon) {
        tailer = excerptCommon instanceof ExcerptTailer ? (ExcerptTailer) excerptCommon : null;
        appender = excerptCommon instanceof ExcerptAppender ? (ExcerptAppender) excerptCommon : null;
        excerpt = excerptCommon instanceof Excerpt ? (Excerpt) excerptCommon : null;
        common = excerptCommon;
    }

    @Override
    public Chronicle chronicle() {
        return common.chronicle();
    }

    @Override
    public boolean nextIndex() {
        return tailer.nextIndex();
    }

    @Override
    public boolean index(long index) throws IndexOutOfBoundsException {
        return excerpt.index(index);
    }

    @Override
    public void startExcerpt(long capacity) {
        appender.startExcerpt(capacity);
    }

    @Override
    public void finish() {
        common.finish();
    }

    @Override
    public long index() {
        return common.index();
    }

    @Override
    public long position() {
        return common.position();
    }

    @Override
    public long capacity() {
        return common.capacity();
    }

    @Override
    public long remaining() {
        return common.remaining();
    }

    @Override
    public void readFully(byte[] bytes) {
        common.readFully(bytes);
    }

    @Override
    public int skipBytes(int n) {
        return common.skipBytes(n);
    }

    @Override
    public void readFully(byte[] b, int off, int len) {
        common.readFully(b, off, len);
    }

    @Override
    public boolean readBoolean() {
        return common.readBoolean();
    }

    @Override
    public boolean readBoolean(long offset) {
        return common.readBoolean(offset);
    }

    @Override
    public int readUnsignedByte() {
        return common.readUnsignedByte();
    }

    @Override
    public int readUnsignedByte(long offset) {
        return common.readUnsignedByte(offset);
    }

    @Override
    public int readUnsignedShort() {
        return common.readUnsignedShort();
    }

    @Override
    public int readUnsignedShort(long offset) {
        return common.readUnsignedShort(offset);
    }

    @Override
    public String readLine() {
        return common.readLine();
    }

    @Override
    public String readUTF() {
        return common.readUTF();
    }

    @Override
    public String readUTFΔ() {
        return common.readUTFΔ();
    }

    @Override
    public void writeBytesΔ(CharSequence s) {
        common.writeBytesΔ(s);
    }

    @Override
    public boolean readUTFΔ(StringBuilder stringBuilder) {
        return common.readUTFΔ(stringBuilder);
    }

    @Override
    public String parseUTF(StopCharTester tester) {
        return common.parseUTF(tester);
    }

    @Override
    public void parseUTF(Appendable builder, StopCharTester tester) {
        common.parseUTF(builder, tester);
    }

    @Override
    public short readCompactShort() {
        return common.readCompactShort();
    }

    @Override
    public int readCompactUnsignedShort() {
        return common.readCompactUnsignedShort();
    }

    @Override
    public int readInt24() {
        return common.readInt24();
    }

    @Override
    public int readInt24(long offset) {
        return common.readInt24(offset);
    }

    @Override
    public long readUnsignedInt() {
        return common.readUnsignedInt();
    }

    @Override
    public long readUnsignedInt(long offset) {
        return common.readUnsignedInt(offset);
    }

    @Override
    public int readCompactInt() {
        return common.readCompactInt();
    }

    @Override
    public long readCompactUnsignedInt() {
        return common.readCompactUnsignedInt();
    }

    @Override
    public long readInt48() {
        return common.readInt48();
    }

    @Override
    public long readInt48(long offset) {
        return common.readInt48(offset);
    }

    @Override
    public long readCompactLong() {
        return common.readCompactLong();
    }

    @Override
    public long readStopBit() {
        return common.readStopBit();
    }

    @Override
    public double readCompactDouble() {
        return common.readCompactDouble();
    }

    @Override
    public void readBytesΔ(StringBuilder sb) {
        common.readBytesΔ(sb);
    }

    @Override
    public void readCharsΔ(StringBuilder sb) {
        common.readCharsΔ(sb);
    }

    @Override
    public void read(ByteBuffer bb) {
        common.read(bb);
    }

    @Override
    public void write(byte[] bytes) {
        common.write(bytes);
    }

    @Override
    public void writeBoolean(boolean v) {
        common.writeBoolean(v);
    }

    @Override
    public void writeBoolean(long offset, boolean v) {
        common.writeBoolean(offset, v);
    }

    @Override

    public void writeBytes(String s) {
        common.writeBytes(s);
    }

    @Override
    public void writeChars(String s) {
        common.writeChars(s);
    }

    @Override
    public void writeUTF(String s) {
        common.writeUTF(s);
    }

    @Override
    public void writeUTFΔ(CharSequence str) {
        common.writeUTFΔ(str);
    }

    @Override
    public void writeByte(int v) {
        common.writeByte(v);
    }

    @Override
    public void writeUnsignedByte(int v) {
        common.writeUnsignedByte(v);
    }

    @Override
    public void writeUnsignedByte(long offset, int v) {
        common.writeUnsignedByte(offset, v);
    }

    @Override
    public void write(long offset, byte[] bytes) {
        common.write(offset, bytes);
    }

    @Override
    public void write(byte[] bytes, int off, int len) {
        common.write(bytes, off, len);
    }

    @Override
    public void writeUnsignedShort(int v) {
        common.writeUnsignedShort(v);
    }

    @Override
    public void writeUnsignedShort(long offset, int v) {
        common.writeUnsignedShort(offset, v);
    }

    @Override
    public void writeCompactShort(int v) {
        common.writeCompactShort(v);
    }

    @Override
    public void writeCompactUnsignedShort(int v) {
        common.writeCompactUnsignedShort(v);
    }

    @Override
    public void writeInt24(int v) {
        common.writeInt24(v);
    }

    @Override
    public void writeInt24(long offset, int v) {
        common.writeInt24(offset, v);
    }

    @Override
    public void writeUnsignedInt(long v) {
        common.writeUnsignedInt(v);
    }

    @Override
    public void writeUnsignedInt(long offset, long v) {
        common.writeUnsignedInt(offset, v);
    }

    @Override
    public void writeCompactInt(int v) {
        common.writeCompactInt(v);
    }

    @Override
    public void writeCompactUnsignedInt(long v) {
        common.writeCompactUnsignedInt(v);
    }

    @Override
    public void writeInt48(long v) {
        common.writeInt48(v);
    }

    @Override
    public void writeInt48(long offset, long v) {
        common.writeInt48(offset, v);
    }

    @Override
    public void writeCompactLong(long v) {
        common.writeCompactLong(v);
    }

    @Override
    public void writeCompactDouble(double v) {
        common.writeCompactDouble(v);
    }

    @Override
    public void write(ByteBuffer bb) {
        common.write(bb);
    }

    @Override
    public ByteStringAppender append(CharSequence s) {
        common.append(s);
        return this;
    }

    @Override
    public ByteStringAppender append(CharSequence s, int start, int end) {
        common.append(s, start, end);
        return this;
    }

    @Override
    public ByteStringAppender append(Enum value) {
        common.append(value);
        return this;
    }

    @Override

    public ByteStringAppender append(byte[] str) {
        common.append(str);
        return this;
    }

    @Override
    public ByteStringAppender append(byte[] str, int offset, int len) {
        common.append(str, offset, len);
        return this;
    }

    @Override
    public ByteStringAppender append(boolean b) {
        common.append(b);
        return this;
    }

    @Override
    public ByteStringAppender append(char c) {
        common.append(c);
        return this;
    }

    @Override
    public ByteStringAppender append(int num) {
        common.append(num);
        return this;
    }

    @Override
    public ByteStringAppender append(long num) {
        common.append(num);
        return this;
    }

    @Override
    public ByteStringAppender append(double d) {
        common.append(d);
        return this;
    }

    @Override
    public ByteStringAppender append(double d, int precision) {
        common.append(d, precision);
        return this;
    }

    @Override
    public ByteStringAppender append(MutableDecimal md) {
        append(md);
        return this;
    }

    @Override
    public double parseDouble() {
        return common.parseDouble();
    }

    @Override
    public long parseLong() {
        return common.parseLong();
    }

    @Override
    public InputStream inputStream() {
        return common.inputStream();
    }

    @Override
    public OutputStream outputStream() {
        return common.outputStream();
    }

    @Override
    public <E> void writeEnum(E o) {
        common.writeEnum(o);
    }

    @Override
    public <E> E readEnum(Class<E> aClass) {
        return common.readEnum(aClass);
    }

    @Override
    public <E> E parseEnum(Class<E> aClass, StopCharTester tester) {
        return common.parseEnum(aClass, tester);
    }

    @Override
    public <K, V> void writeMap(Map<K, V> map) {
        common.writeMap(map);
    }

    @Override
    public <K, V> void readMap(Map<K, V> map, Class<K> kClass, Class<V> vClass) {
        common.readMap(map, kClass, vClass);
    }

    @Override
    public byte readByte() {
        return common.readByte();
    }

    @Override
    public byte readByte(long offset) {
        return common.readByte(offset);
    }

    @Override
    public short readShort() {
        return common.readShort();
    }

    @Override
    public short readShort(long offset) {
        return common.readShort(offset);
    }

    @Override
    public char readChar() {
        return common.readChar();
    }

    @Override
    public char readChar(long offset) {
        return common.readChar(offset);
    }

    @Override
    public int readInt() {
        return common.readInt();
    }

    @Override
    public int readInt(long offset) {
        return common.readInt(offset);
    }

    @Override
    public long readLong() {
        return common.readLong();
    }

    @Override
    public long readLong(long offset) {
        return common.readLong(offset);
    }

    @Override
    public float readFloat() {
        return common.readFloat();
    }

    @Override
    public float readFloat(long offset) {
        return common.readFloat(offset);
    }

    @Override
    public double readDouble() {
        return common.readDouble();
    }

    @Override
    public double readDouble(long offset) {
        return common.readDouble(offset);
    }

    @Override
    public void write(int b) {
        common.write(b);
    }

    @Override
    public void writeByte(long offset, int b) {
        common.writeByte(offset, b);
    }

    @Override
    public void writeShort(int v) {
        common.writeShort(v);
    }

    @Override
    public void writeShort(long offset, int v) {
        common.writeShort(offset, v);
    }

    @Override
    public void writeChar(int v) {
        common.writeChar(v);
    }

    @Override
    public void writeChar(long offset, int v) {
        common.writeChar(offset, v);
    }

    @Override
    public void writeInt(int v) {
        common.writeInt(v);
    }

    @Override
    public void writeInt(long offset, int v) {
        common.writeInt(offset, v);
    }

    @Override
    public void writeLong(long v) {
        common.writeLong(v);
    }

    @Override
    public void writeLong(long offset, long v) {
        common.writeLong(offset, v);
    }

    @Override
    public void writeStopBit(long n) {
        common.writeStopBit(n);
    }

    @Override
    public void writeFloat(float v) {
        common.writeFloat(v);
    }

    @Override
    public void writeFloat(long offset, float v) {
        common.writeFloat(offset, v);
    }

    @Override
    public void writeDouble(double v) {
        common.writeDouble(v);
    }

    @Override
    public void writeDouble(long offset, double v) {
        common.writeDouble(offset, v);
    }

    @Override
    public Object readObject() {
        return common.readObject();
    }

    @Override
    public int read() {
        return common.read();
    }

    @Override
    public int read(byte[] bytes) {
        return common.read(bytes);
    }

    @Override
    public int read(byte[] bytes, int off, int len) {
        return common.read(bytes, off, len);
    }

    @Override
    public long skip(long n) {
        return common.skip(n);
    }

    @Override
    public int available() {
        return common.available();
    }

    @Override
    public void close() {
        try {
            common.close();
        } catch (Exception keepIntelliJHappy) {
        }
    }

    @Override
    public void writeObject(Object obj) {
        common.writeObject(obj);
    }

    @Override
    public void flush() {
        common.flush();
    }

    @Override
    public <E> void writeList(Collection<E> list) {
        common.writeList(list);
    }

    @Override
    public <E> void readList(Collection<E> list, Class<E> eClass) {
        common.readList(list, eClass);
    }

    @Override
    public long size() {
        return common.size();
    }

    @Override
    public boolean stepBackAndSkipTo(StopCharTester tester) {
        return common.stepBackAndSkipTo(tester);
    }

    @Override
    public boolean skipTo(StopCharTester tester) {
        return common.skipTo(tester);
    }

    @Override
    public MutableDecimal parseDecimal(MutableDecimal decimal) {
        return common.parseDecimal(decimal);
    }

    @Override
    public Excerpt toStart() {
        excerpt.toStart();
        return this;
    }

    @Override
    public WrappedExcerpt toEnd() {
        common.toEnd();
        return this;
    }

    @Override
    public boolean isFinished() {
        return common.isFinished();
    }

    @Override
    public boolean wasPadding() {
        return excerpt.wasPadding();
    }

    @Override
    public void roll() {
        appender.roll();
    }

    @Override
    public ByteStringAppender appendTimeMillis(long timeInMS) {
        common.appendTimeMillis(timeInMS);
        return this;
    }

    @Override
    public ByteStringAppender appendDateMillis(long timeInMS) {
        common.appendDateMillis(timeInMS);
        return this;
    }

    @Override
    public ByteStringAppender appendDateTimeMillis(long timeInMS) {
        common.appendDateTimeMillis(timeInMS);
        return this;
    }

    @Override
    public <E> ByteStringAppender append(E object) {
        common.append(object);
        return this;
    }

    @Override
    public <E> ByteStringAppender append(Iterable<E> list, CharSequence seperator) {
        common.append(list, seperator);
        return this;
    }

    @Override
    public <E> ByteStringAppender append(List<E> list, CharSequence seperator) {
        common.append(list, seperator);
        return this;
    }

    @Override
    public int readVolatileInt() {
        return common.readVolatileInt();
    }

    @Override
    public int readVolatileInt(long offset) {
        return common.readVolatileInt(offset);
    }

    @Override
    public long readVolatileLong() {
        return common.readVolatileLong();
    }

    @Override
    public long readVolatileLong(long offset) {
        return common.readVolatileLong(offset);
    }

    @Override
    public void writeOrderedInt(int v) {
        common.writeOrderedInt(v);
    }

    @Override
    public void writeOrderedInt(long offset, int v) {
        common.writeOrderedInt(offset, v);
    }

    @Override
    public boolean compareAndSetInt(long offset, int expected, int x) {
        return common.compareAndSetInt(offset, expected, x);
    }

    @Override
    public int getAndAdd(long offset, int delta) {
        return common.getAndAdd(offset, delta);
    }

    @Override
    public int addAndGetInt(long offset, int delta) {
        return common.addAndGetInt(offset, delta);
    }

    @Override
    public void writeOrderedLong(long v) {
        common.writeOrderedLong(v);
    }

    @Override
    public void writeOrderedLong(long offset, long v) {
        common.writeOrderedLong(offset, v);
    }

    @Override
    public boolean compareAndSetLong(long offset, long expected, long x) {
        return common.compareAndSetLong(offset, expected, x);
    }

    @Override
    public void position(long position) {
        common.position(position);
    }

    @Override
    public ByteOrder byteOrder() {
        return common.byteOrder();
    }

    @Override
    public BytesMarshallerFactory bytesMarshallerFactory() {
        return common.bytesMarshallerFactory();
    }

    @Override
    public void checkEndOfBuffer() throws IndexOutOfBoundsException {
        common.checkEndOfBuffer();
    }

    @Override
    public boolean tryLockInt(long offset) {
        return common.tryLockInt(offset);
    }

    @Override
    public boolean tryLockNanosInt(long offset, long nanos) {
        return common.tryLockNanosInt(offset, nanos);
    }

    @Override
    public void busyLockInt(long offset) throws InterruptedException, IllegalStateException {
        common.busyLockInt(offset);
    }

    @Override
    public void unlockInt(long offset) throws IllegalStateException {
        common.unlockInt(offset);
    }
}
