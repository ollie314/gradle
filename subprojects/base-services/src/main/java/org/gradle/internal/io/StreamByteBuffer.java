/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.internal.io;

import org.gradle.internal.UncheckedException;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.LinkedList;


/**
 * An in-memory buffer that provides OutputStream and InputStream interfaces.
 *
 * This is more efficient than using ByteArrayOutputStream/ByteArrayInputStream
 *
 * Reading the buffer will clear the buffer.
 * This is not thread-safe, it is intended to be used by a single Thread.
 */
public class StreamByteBuffer {
    private static final int DEFAULT_CHUNK_SIZE = 4096;
    private static final int MAX_CHUNK_SIZE = 1024 * 1024;
    private LinkedList<StreamByteBufferChunk> chunks = new LinkedList<StreamByteBufferChunk>();
    private StreamByteBufferChunk currentWriteChunk;
    private StreamByteBufferChunk currentReadChunk;
    private int chunkSize;
    private int nextChunkSize;
    private int maxChunkSize;
    private StreamByteBufferOutputStream output;
    private StreamByteBufferInputStream input;
    private int totalBytesUnreadInList;

    public StreamByteBuffer() {
        this(DEFAULT_CHUNK_SIZE);
    }

    public StreamByteBuffer(int chunkSize) {
        this.chunkSize = chunkSize;
        this.nextChunkSize = chunkSize;
        this.maxChunkSize = Math.max(chunkSize, MAX_CHUNK_SIZE);
        currentWriteChunk = new StreamByteBufferChunk(nextChunkSize);
        output = new StreamByteBufferOutputStream();
        input = new StreamByteBufferInputStream();
    }

    public static StreamByteBuffer of(InputStream inputStream) throws IOException {
        StreamByteBuffer buffer = new StreamByteBuffer(chunkSizeInDefaultRange(inputStream.available()));
        buffer.readFully(inputStream);
        return buffer;
    }

    public static StreamByteBuffer of(InputStream inputStream, int len) throws IOException {
        StreamByteBuffer buffer = new StreamByteBuffer(chunkSizeInDefaultRange(len));
        buffer.readFrom(inputStream, len);
        return buffer;
    }

    public static StreamByteBuffer createWithChunkSizeInDefaultRange(int value) {
        return new StreamByteBuffer(chunkSizeInDefaultRange(value));
    }

    static int chunkSizeInDefaultRange(int value) {
        return valueInRange(value, DEFAULT_CHUNK_SIZE, MAX_CHUNK_SIZE);
    }

    private static int valueInRange(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    public OutputStream getOutputStream() {
        return output;
    }

    public InputStream getInputStream() {
        return input;
    }

    public void writeTo(OutputStream target) throws IOException {
        while (prepareRead() != -1) {
            currentReadChunk.writeTo(target);
        }
    }

    public void readFrom(InputStream inputStream, int len) throws IOException {
        int bytesLeft = len;
        while (bytesLeft > 0) {
            int spaceLeft = allocateSpace();
            int limit = Math.min(spaceLeft, bytesLeft);
            int readBytes = currentWriteChunk.readFrom(inputStream, limit);
            if (readBytes == -1) {
                throw new EOFException("Unexpected EOF");
            }
            bytesLeft -= readBytes;
        }
    }

    public void readFully(InputStream inputStream) throws IOException {
        while (true) {
            int len = allocateSpace();
            int readBytes = currentWriteChunk.readFrom(inputStream, len);
            if (readBytes == -1) {
                break;
            }
        }
    }

    public byte[] readAsByteArray() {
        byte[] buf = new byte[totalBytesUnread()];
        input.readImpl(buf, 0, buf.length);
        return buf;
    }

    public String readAsString(String encoding) {
        Charset charset = Charset.forName(encoding);
        return readAsString(charset);
    }

    public String readAsString() {
        return readAsString(Charset.defaultCharset());
    }

    public String readAsString(Charset charset) {
        try {
            return doReadAsString(charset);
        } catch (CharacterCodingException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    private String doReadAsString(Charset charset) throws CharacterCodingException {
        int unreadSize = totalBytesUnread();
        if (unreadSize > 0) {
            CharsetDecoder decoder = charset.newDecoder().onMalformedInput(
                    CodingErrorAction.REPLACE).onUnmappableCharacter(
                    CodingErrorAction.REPLACE);
            CharBuffer charbuffer = CharBuffer.allocate(unreadSize);
            ByteBuffer buf = null;
            while (prepareRead() != -1) {
                buf = currentReadChunk.readToNioBuffer(buf);
                boolean endOfInput = prepareRead() == -1;
                CoderResult result = decoder.decode(buf, charbuffer, endOfInput);
                if (endOfInput) {
                    if (!result.isUnderflow()) {
                        result.throwException();
                    }
                }
            }
            CoderResult result = decoder.flush(charbuffer);
            if (buf.hasRemaining()) {
                throw new IllegalStateException("There's a bug here, buffer wasn't read fully.");
            }
            if (!result.isUnderflow()) {
                result.throwException();
            }
            charbuffer.flip();
            return charbuffer.toString();
        }
        return "";
    }

    public int totalBytesUnread() {
        int total = totalBytesUnreadInList;
        if (currentReadChunk != null) {
            total += currentReadChunk.bytesUnread();
        }
        if (currentWriteChunk != currentReadChunk && currentWriteChunk != null) {
            total += currentWriteChunk.bytesUnread();
        }
        return total;
    }

    protected int allocateSpace() {
        int spaceLeft = currentWriteChunk.spaceLeft();
        if (spaceLeft == 0) {
            chunks.add(currentWriteChunk);
            totalBytesUnreadInList += currentWriteChunk.bytesUnread();
            currentWriteChunk = new StreamByteBufferChunk(nextChunkSize);
            if (nextChunkSize < maxChunkSize) {
                nextChunkSize = Math.min(nextChunkSize * 2, maxChunkSize);
            }
            spaceLeft = currentWriteChunk.spaceLeft();
        }
        return spaceLeft;
    }

    protected int prepareRead() {
        int bytesUnread = (currentReadChunk != null) ? currentReadChunk.bytesUnread() : 0;
        if (bytesUnread == 0) {
            if (!chunks.isEmpty()) {
                currentReadChunk = chunks.removeFirst();
                bytesUnread = currentReadChunk.bytesUnread();
                totalBytesUnreadInList -= bytesUnread;
            } else if (currentReadChunk != currentWriteChunk) {
                currentReadChunk = currentWriteChunk;
                bytesUnread = currentReadChunk.bytesUnread();
            } else {
                bytesUnread = -1;
            }
        }
        return bytesUnread;
    }

    class StreamByteBufferChunk {
        private int pointer;
        private byte[] buffer;
        private int size;
        private int used;

        public StreamByteBufferChunk(int size) {
            this.size = size;
            buffer = new byte[size];
        }

        public ByteBuffer readToNioBuffer(ByteBuffer previousBufferToMergeWith) {
            if (pointer < used) {
                ByteBuffer result;
                if (previousBufferToMergeWith != null && previousBufferToMergeWith.hasRemaining()) {
                    // merge previous buffer if it has remaining bytes
                    result = ByteBuffer.allocate(previousBufferToMergeWith.remaining() + bytesUnread()).put(previousBufferToMergeWith).put(buffer, pointer, used - pointer);
                    result.flip();
                } else {
                    if (pointer > 0 || used < size) {
                        result = ByteBuffer.wrap(buffer, pointer, used - pointer);
                    } else {
                        result = ByteBuffer.wrap(buffer);
                    }
                }
                pointer = used;
                return result;
            }

            return null;
        }

        public boolean write(byte b) {
            if (used < size) {
                buffer[used++] = b;
                return true;
            }

            return false;
        }

        public void write(byte[] b, int off, int len) {
            System.arraycopy(b, off, buffer, used, len);
            used = used + len;
        }

        public void read(byte[] b, int off, int len) {
            System.arraycopy(buffer, pointer, b, off, len);
            pointer = pointer + len;
        }

        public void writeTo(OutputStream target) throws IOException {
            if (pointer < used) {
                target.write(buffer, pointer, used - pointer);
                pointer = used;
            }
        }

        public void reset() {
            pointer = 0;
        }

        public int bytesUsed() {
            return used;
        }

        public int bytesUnread() {
            return used - pointer;
        }

        public int read() {
            if (pointer < used) {
                return buffer[pointer++] & 0xff;
            }

            return -1;
        }

        public int spaceLeft() {
            return size - used;
        }

        public int readFrom(InputStream inputStream, int len) throws IOException {
            int readBytes = inputStream.read(buffer, used, len);
            if(readBytes > 0) {
                used += readBytes;
            }
            return readBytes;
        }
    }

    class StreamByteBufferOutputStream extends OutputStream {
        private boolean closed;

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (b == null) {
                throw new NullPointerException();
            }

            if ((off < 0) || (off > b.length) || (len < 0)
                    || ((off + len) > b.length) || ((off + len) < 0)) {
                throw new IndexOutOfBoundsException();
            }

            if (len == 0) {
                return;
            }

            int bytesLeft = len;
            int currentOffset = off;
            while (bytesLeft > 0) {
                int spaceLeft = allocateSpace();
                int writeBytes = Math.min(spaceLeft, bytesLeft);
                currentWriteChunk.write(b, currentOffset, writeBytes);
                bytesLeft -= writeBytes;
                currentOffset += writeBytes;
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
        }

        public boolean isClosed() {
            return closed;
        }

        @Override
        public void write(int b) throws IOException {
            allocateSpace();
            currentWriteChunk.write((byte) b);
        }

        public StreamByteBuffer getBuffer() {
            return StreamByteBuffer.this;
        }
    }

    class StreamByteBufferInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            prepareRead();
            return currentReadChunk.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return readImpl(b, off, len);
        }

        int readImpl(byte[] b, int off, int len) {
            if (b == null) {
                throw new NullPointerException();
            }

            if ((off < 0) || (off > b.length) || (len < 0)
                    || ((off + len) > b.length) || ((off + len) < 0)) {
                throw new IndexOutOfBoundsException();
            }

            if (len == 0) {
                return 0;
            }

            int bytesLeft = len;
            int currentOffset = off;
            int bytesUnread = prepareRead();
            int totalBytesRead = 0;
            while (bytesLeft > 0 && bytesUnread != -1) {
                int readBytes = Math.min(bytesUnread, bytesLeft);
                currentReadChunk.read(b, currentOffset, readBytes);
                bytesLeft -= readBytes;
                currentOffset += readBytes;
                totalBytesRead += readBytes;
                bytesUnread = prepareRead();
            }
            if (totalBytesRead > 0) {
                return totalBytesRead;
            }

            return -1;
        }

        @Override
        public int available() throws IOException {
            return totalBytesUnread();
        }

        public StreamByteBuffer getBuffer() {
            return StreamByteBuffer.this;
        }
    }

    public void clear() {
        chunks.clear();
        currentReadChunk = null;
        totalBytesUnreadInList = 0;
        nextChunkSize = chunkSize;
        currentWriteChunk = new StreamByteBufferChunk(nextChunkSize);
    }
}
