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

package net.openhft.chronicle.wire;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.core.pool.ClassLookup;
import net.openhft.chronicle.core.values.LongValue;
import net.openhft.chronicle.threads.BusyPauser;
import net.openhft.chronicle.threads.LongPauser;
import net.openhft.chronicle.threads.Pauser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static net.openhft.chronicle.wire.Wires.*;

/**
 * Created by peter on 10/03/16.
 */
public abstract class AbstractWire implements Wire {
    protected static final boolean ASSERTIONS;

    static {
        boolean assertions = false;
        assert assertions = true;
        ASSERTIONS = assertions;
    }

    protected final Bytes<?> bytes;
    protected final boolean use8bit;
    protected Pauser pauser = BusyPauser.INSTANCE;
    protected ClassLookup classLookup = ClassAliasPool.CLASS_ALIASES;
    protected Object parent;
    volatile Thread usedBy;
    volatile Throwable usedHere, lastEnded;
    int usedCount = 0;
    private long headerNumber = Long.MIN_VALUE;
    private boolean notCompleteIsNotPresent;
    private ObjectOutput objectOutput;
    private ObjectInput objectInput;

    private boolean insideHeader;

    public AbstractWire(@NotNull Bytes bytes, boolean use8bit) {
        this.bytes = bytes;
        this.use8bit = use8bit;
        notCompleteIsNotPresent = bytes.sharedMemory();
    }

    private static long throwNotEnoughSpace(int maxlen, Bytes<?> bytes) {
        throw new IllegalStateException("not enough space to write " + maxlen + " was " + bytes.writeRemaining());
    }

    private static void throwHeaderOverwritten(long position, int expectedHeader, Bytes<?> bytes) throws StreamCorruptedException {
        throw new StreamCorruptedException("Data at " + position + " overwritten? Expected: " + Integer.toHexString(expectedHeader) + " was " + Integer.toHexString(bytes.readVolatileInt(position)));
    }

    private static void throwLengthMismatch(int length, int actualLength) throws StreamCorruptedException {
        throw new StreamCorruptedException("Wrote " + actualLength + " when " + length + " was set initially.");
    }

    private static String exceptionStacktraceToString(Exception e) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        e.printStackTrace(ps);
        ps.close();
        return baos.toString();
    }

    public boolean isInsideHeader() {
        return this.insideHeader;
    }

    @Override
    public Pauser pauser() {
        if (pauser == BusyPauser.INSTANCE)
            pauser = new LongPauser(1_000, 500, 1, 10, TimeUnit.MILLISECONDS);
        return pauser;
    }

    @Override
    public void pauser(Pauser pauser) {
        this.pauser = pauser;
    }

    @Override
    public void clear() {
        bytes.clear();
        headerNumber(Long.MIN_VALUE);
    }

    @Override
    public Wire headerNumber(long headerNumber) {

      /*  assert checkThread();

        System.out.println("thread=" + Thread.currentThread().getName() + ",pos=" + pos + ",was=" + Long.toHexString(this
                .headerNumber) + ",isInsideHeader=" + isInsideHeader() +
                "is=" + Long.toHexString(headerNumber) + ",at=" + exceptionStacktraceToString(new RuntimeException()))
        ;*/
        this.headerNumber = headerNumber;
        return this;
    }

    @Override
    public long headerNumber() {
        return headerNumber;
    }

    @Override
    public void classLookup(ClassLookup classLookup) {
        this.classLookup = classLookup;
    }

    @Override
    public ClassLookup classLookup() {
        return classLookup;
    }

    @NotNull
    @Override
    public Bytes<?> bytes() {
        return bytes;
    }

    @Override
    public boolean hasMore() {
        consumePadding();
        return bytes.readRemaining() > 0;
    }

    @Override
    public HeaderType readDataHeader(boolean includeMetaData) throws EOFException {

        for (; ; ) {
            int header = bytes.peekVolatileInt();
            if (isReady(header)) {
                if (header == NOT_INITIALIZED)
                    return HeaderType.NONE;
                if (isData(header))
                    return HeaderType.DATA;
                if (includeMetaData && isReadyMetaData(header))
                    return HeaderType.META_DATA;

                int bytesToSkip = lengthOf(header) + SPB_HEADER_SIZE;
                bytes.readSkip(bytesToSkip);
            } else {
                if (header == END_OF_DATA)
                    throw new EOFException();
                return HeaderType.NONE;
            }
        }
    }

    @Override
    public void readAndSetLength(long position) {
        int header = bytes.peekVolatileInt();
        if (isReady(header)) {
            if (header == NOT_INITIALIZED)
                throw new IllegalStateException();
            long start = position + SPB_HEADER_SIZE;
            bytes.readPositionRemaining(start, lengthOf(header));
            return;
        }
        throw new IllegalStateException();
    }

    @Override
    public void readMetaDataHeader() {
        int header = bytes.peekVolatileInt();
        if (isReady(header)) {
            if (header == NOT_INITIALIZED)
                throw new IllegalStateException("Meta data not initialised");
            if (isReadyMetaData(header)) {
                setLimitPosition(header);
                return;
            }
        }
        throw new IllegalStateException("Meta data not ready " + Integer.toHexString(header));
    }

    private void setLimitPosition(int header) {
        bytes.readLimit(bytes.readPosition() + lengthOf(header) + SPB_HEADER_SIZE)
                .readSkip(SPB_HEADER_SIZE);
    }

    @Override
    public void readFirstHeader(long timeout, TimeUnit timeUnit) throws TimeoutException, StreamCorruptedException {
        int header;
        for (; ; ) {
            header = bytes.readVolatileInt(0L);
            if (isReady(header)) {
                break;
            }
            pauser.pause(timeout, timeUnit);
        }
        pauser.reset();
        int len = lengthOf(header);
        if (!isReadyMetaData(header) || len > 64 << 10)
            throw new StreamCorruptedException("Unexpected magic number " + Integer.toHexString(header));
        bytes.readPositionRemaining(SPB_HEADER_SIZE, len);
    }

    @Override
    public long writeHeader(int length, long timeout, TimeUnit timeUnit, @Nullable LongValue
            lastPosition) throws TimeoutException, EOFException {

        if (insideHeader)
            throw new AssertionError("you cant put a header inside a header, check that " +
                    "you have not nested the documents.");

        insideHeader = true;

        if (length < 0 || length > MAX_LENGTH)
            throw new IllegalArgumentException();
        long pos = bytes.writePosition();

        if (bytes.compareAndSwapInt(pos, 0, NOT_COMPLETE | length)) {
            int maxlen = length == UNKNOWN_LENGTH ? MAX_LENGTH : length;
            if (maxlen > bytes.writeRemaining())
                return throwNotEnoughSpace(maxlen, bytes);
            bytes.writePositionRemaining(pos + SPB_HEADER_SIZE, maxlen);
//            System.out.println(Thread.currentThread()+" wpr pos: "+pos+" hdr "+headerNumber);
            return pos;
        }

        if (lastPosition != null) {
            long lastPositionValue = lastPosition.getValue();
            // do we jump forward if there has been writes else where.
            if (lastPositionValue > bytes.writePosition() + 1 << 20) {
                headerNumber(Long.MIN_VALUE);
                bytes.writePosition(lastPositionValue);
//                System.out.println(Thread.currentThread()+" last pos: "+lastPositionValue+" hdr "+headerNumber);
            }
        }

        return writeHeader0(length, timeout, timeUnit);
    }

    private long writeHeader0(int length, long timeout, TimeUnit timeUnit) throws TimeoutException, EOFException {
        if (length < 0 || length > MAX_LENGTH)
            throw new IllegalArgumentException();
        long pos = bytes.writePosition();
        pauser();
//        System.out.println(Thread.currentThread()+" wh0 pos: "+pos+" hdr "+(int) headerNumber);
        try {
            for (; ; ) {
                if (bytes.compareAndSwapInt(pos, 0, NOT_COMPLETE | length)) {

                    bytes.writePosition(pos + SPB_HEADER_SIZE);
                    int maxlen = length == UNKNOWN_LENGTH ? MAX_LENGTH : length;
                    if (maxlen > bytes.writeRemaining())
                        throwNotEnoughSpace(maxlen, bytes);
                    bytes.writeLimit(bytes.writePosition() + maxlen);
                    return pos;
                }
                pauser.pause(timeout, timeUnit);
                int header = bytes.readVolatileInt(pos);
                // two states where it is unable to continue.
                if (header == END_OF_DATA)
                    throw new EOFException();
                if (isNotComplete(header))
                    continue;

                int len = lengthOf(header);
                pos += len + SPB_HEADER_SIZE; // length of message plus length of header

                if (isData(header))
                    incrementHeaderNumber();
//                System.out.println(Thread.currentThread()+" wh0-iter pos: "+pos+" hdr "+(int) headerNumber);

            }
        } finally {
            pauser.reset();
        }
    }

    @Override
    public void updateHeader(int length, long position, boolean metaData) throws
            StreamCorruptedException {

        // the reason we add padding is so that a message gets sent ( this is, mostly for queue as
        // it cant handle a zero len message )
        if (bytes.writePosition() == position + 4)
            addPadding(1);

        long pos = bytes.writePosition();
        int actualLength = Maths.toUInt31(pos - position - 4);

        int expectedHeader = NOT_COMPLETE | length;
        if (length == UNKNOWN_LENGTH)
            length = actualLength;
        else if (length < actualLength)
            throwLengthMismatch(length, actualLength);
        int header = length;
        if (metaData) header |= META_DATA;
        if (header == UNKNOWN_LENGTH)
            throw new UnsupportedOperationException("Data messages of 0 length are not supported.");

        assert insideHeader;
        insideHeader = false;

        if (ASSERTIONS) {
            updateHeaderAssertions(position, pos, expectedHeader, header);
        } else {
            bytes.writeOrderedInt(position, header);
        }
        bytes.writeLimit(bytes.capacity());
        if (!metaData)
            incrementHeaderNumber();
    }

    void updateHeaderAssertions(long position, long pos, int expectedHeader, int header) throws StreamCorruptedException {
        checkNoDataAfterEnd(pos);

        if (!bytes.compareAndSwapInt(position, expectedHeader, header))
            throw new StreamCorruptedException("Data at " + position + " overwritten? Expected: " + Integer.toHexString(expectedHeader) + " was " + Integer.toHexString(bytes.readVolatileInt(position)));
    }

    protected void checkNoDataAfterEnd(long pos) {
        if (pos <= bytes.realCapacity() - 4) {
            final int value = bytes.bytesStore().readVolatileInt(pos);
            if (value != 0) {
                String text;
                long pos0 = bytes.readPosition();
                try {
                    bytes.readPosition(pos);
                    text = bytes.toDebugString();
                } finally {
                    bytes.readPosition(pos0);
                }
                throw new IllegalStateException("Data was written after the end of the message, zero out data before rewinding " + text);
            }
        }
    }

    private void incrementHeaderNumber() {
        if (headerNumber != Long.MIN_VALUE)
            headerNumber(headerNumber + 1);
    }

    @Override
    public boolean writeFirstHeader() {
        boolean cas = bytes.compareAndSwapInt(0L, 0, NOT_COMPLETE_UNKNOWN_LENGTH);
        if (cas)
            bytes.writeSkip(SPB_HEADER_SIZE);
        return cas;
    }

    @Override
    public void updateFirstHeader() {
        long pos = bytes.writePosition();
        long actualLength = pos - SPB_HEADER_SIZE;
        if (actualLength >= 1 << 30)
            throw new IllegalStateException("Header too large was " + actualLength);
        int header = (int) (META_DATA | actualLength);
        if (!bytes.compareAndSwapInt(0L, NOT_COMPLETE_UNKNOWN_LENGTH, header))
            throw new IllegalStateException("Data at 0 overwritten? Expected: " + Integer.toHexString(NOT_COMPLETE_UNKNOWN_LENGTH) + " was " + Integer.toHexString(bytes.readVolatileInt(0L)));
    }

    @Override
    public void writeEndOfWire(long timeout, TimeUnit timeUnit) throws TimeoutException {
        long pos = bytes.writePosition();
        try {
            for (; ; ) {
                if (bytes.compareAndSwapInt(pos, 0, END_OF_DATA)) {
                    bytes.writePosition(pos + SPB_HEADER_SIZE);
                    return;
                }
                pauser.pause(timeout, timeUnit);
                int header = bytes.readVolatileInt(pos);
                // two states where it is unable to continue.
                if (header == END_OF_DATA)
                    return; // already written.
                if (header == NOT_COMPLETE_UNKNOWN_LENGTH)
                    continue;
                int len = lengthOf(header);
                pos += len + SPB_HEADER_SIZE; // length of message plus length of header
            }
        } finally {
            pauser.reset();
        }
    }

    public Object parent() {
        return parent;
    }

    public void parent(Object parent) {
        this.parent = parent;
    }

    @Override
    public boolean startUse() {
        Throwable usedHere = this.usedHere;
        Thread usedBy = this.usedBy;
        if (usedBy != Thread.currentThread() && usedBy != null) {
            throw new IllegalStateException("Used by " + usedBy + " while trying to use it in " + Thread.currentThread(), usedHere);
        }
        this.usedBy = Thread.currentThread();
        this.usedHere = new Throwable();
        usedCount++;
        return true;
    }

    @Override
    public boolean endUse() {
        if (usedBy != Thread.currentThread()) {
            throw new IllegalStateException("Used by " + usedHere, usedHere);
        }
        if (--usedCount <= 0) {
            usedBy = null;
            usedHere = null;
            usedCount = 0;
            lastEnded = new Throwable();
        }
        return true;
    }

    public boolean notCompleteIsNotPresent() {
        return notCompleteIsNotPresent;
    }

    public void notCompleteIsNotPresent(boolean notCompleteIsNotPresent) {
        this.notCompleteIsNotPresent = notCompleteIsNotPresent;
    }

    public ObjectOutput objectOutput() {
        if (objectOutput == null)
            objectOutput = new WireObjectOutput(this);
        return objectOutput;
    }

    public ObjectInput objectInput() {
        if (objectInput == null)
            objectInput = new WireObjectInput(this);
        return objectInput;
    }
}
