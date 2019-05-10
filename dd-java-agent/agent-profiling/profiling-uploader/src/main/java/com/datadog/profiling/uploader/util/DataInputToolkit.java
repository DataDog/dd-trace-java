/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The contents of this file are subject to the terms of either the Universal Permissive License
 * v 1.0 as shown at http://oss.oracle.com/licenses/upl
 *
 * or the following license:
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions
 * and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided with
 * the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.datadog.profiling.uploader.util;

/**
 * Copy of JMC internal class. We should probably add the streaming JFR splitter, once developed, to
 * the JMC project, so that we can use this class. ;)
 *
 * @author Marcus Hirt
 */
public class DataInputToolkit {

  public static final byte BYTE_SIZE = 1;
  public static final byte BOOLEAN_SIZE = 1;
  public static final byte SHORT_SIZE = 2;
  public static final byte CHAR_SIZE = 2;
  public static final byte INTEGER_SIZE = 4;
  public static final byte LONG_SIZE = 8;
  public static final byte FLOAT_SIZE = 4;
  public static final byte DOUBLE_SIZE = 8;

  public static int readUnsignedByte(final byte[] bytes, final int offset) {
    return bytes[offset] & 0xFF;
  }

  public static byte readByte(final byte[] bytes, final int offset) {
    return bytes[offset];
  }

  public static int readUnsignedShort(final byte[] bytes, final int offset) {
    final int ch1 = (bytes[offset] & 0xff);
    final int ch2 = (bytes[offset + 1] & 0xff);
    return (ch1 << 8) + (ch2 << 0);
  }

  public static short readShort(final byte[] bytes, final int offset) {
    return (short) readUnsignedShort(bytes, offset);
  }

  public static char readChar(final byte[] bytes, final int offset) {
    return (char) readUnsignedShort(bytes, offset);
  }

  public static long readUnsignedInt(final byte[] bytes, final int index) {
    return readInt(bytes, index) & 0xffffffffL;
  }

  public static int readInt(final byte[] bytes, final int index) {
    final int ch1 = (bytes[index] & 0xff);
    final int ch2 = (bytes[index + 1] & 0xff);
    final int ch3 = (bytes[index + 2] & 0xff);
    final int ch4 = (bytes[index + 3] & 0xff);
    return ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + (ch4 << 0));
  }

  public static long readLong(final byte[] bytes, final int index) {
    return (((long) bytes[index + 0] << 56)
        + ((long) (bytes[index + 1] & 255) << 48)
        + ((long) (bytes[index + 2] & 255) << 40)
        + ((long) (bytes[index + 3] & 255) << 32)
        + ((long) (bytes[index + 4] & 255) << 24)
        + ((bytes[index + 5] & 255) << 16)
        + ((bytes[index + 6] & 255) << 8)
        + ((bytes[index + 7] & 255) << 0));
  }

  public static float readFloat(final byte[] bytes, final int offset) {
    return Float.intBitsToFloat(readInt(bytes, offset));
  }

  public static double readDouble(final byte[] bytes, final int offset) {
    return Double.longBitsToDouble(readLong(bytes, offset));
  }

  public static boolean readBoolean(final byte[] bytes, final int offset) {
    return bytes[offset] != 0;
  }
}
