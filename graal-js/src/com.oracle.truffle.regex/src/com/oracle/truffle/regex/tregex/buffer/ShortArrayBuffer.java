/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.regex.tregex.buffer;

import java.util.Arrays;

/**
 * This class is designed as a "scratchpad" for generating many short arrays of unknown size. It
 * will never shrink its internal buffer, so it should be disposed as soon as it is no longer
 * needed.
 * <p>
 * Usage Example:
 * </p>
 * 
 * <pre>
 * ShortArrayBuffer buf = new ShortArrayBuffer();
 * List<short[]> results = new ArrayList<>();
 * for (Object obj : listOfThingsToProcess) {
 *     for (Object x : obj.thingsThatShouldBecomeShorts()) {
 *         buf.add(someCalculation(x));
 *     }
 *     results.add(buf.toArray());
 *     buf.clear();
 * }
 * </pre>
 */
public class ShortArrayBuffer {

    private short[] buf;
    private int size = 0;

    public ShortArrayBuffer() {
        this(16);
    }

    public ShortArrayBuffer(int initialSize) {
        buf = new short[initialSize];
    }

    public void clear() {
        size = 0;
    }

    public int size() {
        return size;
    }

    public short get(int i) {
        return buf[i];
    }

    public void add(short s) {
        if (size == buf.length) {
            grow(size * 2);
        }
        buf[size] = s;
        size++;
    }

    private void grow(int newSize) {
        buf = Arrays.copyOf(buf, newSize);
    }

    public short[] toArray() {
        return Arrays.copyOf(buf, size);
    }
}
