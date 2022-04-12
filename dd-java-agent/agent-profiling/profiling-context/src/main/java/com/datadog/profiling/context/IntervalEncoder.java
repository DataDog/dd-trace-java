package com.datadog.profiling.context;

import java.nio.ByteBuffer;

/**
 * Encodes the collected interval into a binary compressed format.
 *
 * <p>The binary blob can be split into a prologue and data chunk whereas the data chunk immediately
 * follows the prologue. <br>
 *
 * <p>The prologue consists of a header and thread map.
 *
 * <p>The header is containing information about the number of covered threads, the start timestamp
 * in ticks and the frequency multiplier allowing to convert ticks to nanoseconds.
 *
 * <p>The thread map is a list of <b>[thread id, interval count]</b> tuples. <br>
 *
 * <p>The data chunk holds the list of intervals in the form of delta encoded offsets against the
 * start timestamp.
 *
 * <p>Each thread has its own delta encoded sequence - meaning that the start of the first interval
 * for each thread is encoded as the delta against the blob start timestamp, whereas the rest of the
 * timestamps are delta encoded against the previous timestamp. In this sequence each two subsequent
 * timestamps are forming an interval entry. <br>
 *
 * <p>All the timestamp delta values are further <a
 * href="https://github.com/facebook/folly/blob/main/folly/docs/GroupVarint.md">group varint
 * encoded</a> with the assumption that the most of the deltas will fit into 1 or 2 bytes. <br>
 *
 * <p>The bitmap describing the size of each group varint encoded value in number of byte is located
 * at the end of the data chunk. <br>
 * <hr>
 *
 * <h2>Formal description</h2>
 *
 * <p>
 *
 * <h3>Prologue Layout</h3>
 *
 * <h4>Header</h4>
 *
 * <table style="border: 1px solid black">
 *     <tr>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Attribute</td>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Description</th>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Byte Size</th>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Compression</th>
 *     </tr>
 *     <tr bgcolor="#dddddd">
 *       <td style="padding-left: 5px; padding-right: 5px;">Data Chunk Offset</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">Offset to the data chunk start</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">4 bytes</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">RAW</td>
 *     </tr>
 *     <tr bgcolor="#eeeeee">
 *       <td style="padding-left: 5px; padding-right: 5px;">Start Epoch Millis</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">The system epoch millis to peg the start ticks againstt</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">1-9 bytes</td>
 *       <td style="padding-left: 5px; padding-right: 5px;"><a href="https://en.wikipedia.org/wiki/LEB128#Unsigned_LEB128">LEB128 Varint</a></td>
 *     </tr>
 *     <tr bgcolor="#dddddd">
 *       <td style="padding-left: 5px; padding-right: 5px;">Frequency Multiplier</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">The number of ticks per 1000ns</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">1-5 bytes</td>
 *       <td style="padding-left: 5px; padding-right: 5px;"><a href="https://en.wikipedia.org/wiki/LEB128#Unsigned_LEB128">LEB128 Varint</a></td>
 *     </tr>
 *     <tr bgcolor="#eeeeee">
 *       <td style="padding-left: 5px; padding-right: 5px;">Number of Threads</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">Number of threads with recorded intervals</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">1-5 bytes</td>
 *       <td style="padding-left: 5px; padding-right: 5px;"><a href="https://en.wikipedia.org/wiki/LEB128#Unsigned_LEB128">LEB128 Varint</a></td>
 *     </tr>
 *   </table>
 *
 * <br>
 *
 * <h4>Thread Map</h4>
 *
 * <table style="border: 1px solid black">
 *     <tr>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Attribute</td>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Description</th>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Byte Size</th>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Compression</th>
 *     </tr>
 *     <tr bgcolor="#dddddd">
 *       <td style="padding-left: 5px; padding-right: 5px;">Thread ID</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">The Java thread ID</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">1-5 bytes</td>
 *       <td style="padding-left: 5px; padding-right: 5px;"><a href="https://en.wikipedia.org/wiki/LEB128#Unsigned_LEB128">LEB128 Varint</a></td>
 *     </tr>
 *     <tr bgcolor="#eeeeee">
 *       <td style="padding-left: 5px; padding-right: 5px;">Number of Intervals</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">The number of intervals stored for this thread</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">1-5 bytes</td>
 *       <td style="padding-left: 5px; padding-right: 5px;"><a href="https://en.wikipedia.org/wiki/LEB128#Unsigned_LEB128">LEB128 Varint</a></td>
 *     </tr>
 *   </table>
 *
 * <br>
 *
 * <h3>Data Chunk Layout</h3>
 *
 * <h4>Header</h4>
 *
 * <table style="border: 1px solid black">
 *     <tr>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Attribute</td>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Description</th>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Byte Size</th>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Compression</th>
 *     </tr>
 *     <tr bgcolor="#dddddd">
 *       <td style="padding-left: 5px; padding-right: 5px;">Group Varint Bitmap Offset</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">Offset to the group varint size bitmap</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">4 bytes</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">RAW</td>
 *     </tr>
 *   </table>
 *
 * <br>
 *
 * <h4>Thread interval sequence</h4>
 *
 * <i>The following tuple is repeated for as many times as there are intervals for the given
 * thread.</i>
 *
 * <table style="border: 1px solid black">
 *     <tr>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Attribute</td>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Description</th>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Byte Size</th>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Compression</th>
 *     </tr>
 *     <tr bgcolor="#dddddd">
 *       <td style="padding-left: 5px; padding-right: 5px;">Start delta</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">Delta ticks to the end of the previous interval or to the blob start timestamp if this is the first interval</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">1-4 bytes</td>
 *       <td style="padding-left: 5px; padding-right: 5px;"><a href="https://github.com/facebook/folly/blob/main/folly/docs/GroupVarint.md">Group Varint</a></td>
 *     </tr>
 *     <tr bgcolor="#eeeeee">
 *       <td style="padding-left: 5px; padding-right: 5px;">End delta</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">Delta ticks to the start of this interval</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">1-4 bytes</td>
 *       <td style="padding-left: 5px; padding-right: 5px;"><a href="https://github.com/facebook/folly/blob/main/folly/docs/GroupVarint.md">Group Varint</a></td>
 *     </tr>
 *   </table>
 *
 * <br>
 *
 * <h4>Group Varint Size Bitmap</h4>
 *
 * The group varint encoding of long (8 bytes) numbers is using a separate size map where each 8
 * subsequent long numbers are encoded in the correspondingly subsequent 3 bytes where each 3 bits
 * are representing the number of bytes the referenced long number can be represented in. The value
 * of those 3 bits is offset by 1 meaning that if the value is 0b000 the encoded length becomes 1
 * and 0b111 is actually 8.<br>
 * <br>
 * <i>The following triple is repeated as many times as necessary to cover all intervals</i>
 *
 * <table style="border: 1px solid black">
 *     <tr>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Attribute</td>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Description</th>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Byte Size</th>
 *       <th style="border: 1px solid black; padding-left: 10px; padding-right: 5px;">Compression</th>
 *     </tr>
 *     <tr bgcolor="#dddddd">
 *       <td style="padding-left: 5px; padding-right: 5px;">byte 1</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">The following long number lengths are encoded in this byte - <b>[111_222_33]</b></td>
 *       <td style="padding-left: 5px; padding-right: 5px;">1 byte</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">RAW</td>
 *     </tr>
 *     <tr bgcolor="#eeeeee">
 *       <td style="padding-left: 5px; padding-right: 5px;">byte 2</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">The following long number lengths are encoded in this byte - <b>[3_444_555_6]</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">1 byte</td>
 *  *       <td style="padding-left: 5px; padding-right: 5px;">RAW</td>
 *     </tr>
 *     <tr bgcolor="#dddddd">
 *       <td style="padding-left: 5px; padding-right: 5px;">byte 3</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">The following long number lengths are encoded in this byte - <b>[66_777_888]</b></td>
 *       <td style="padding-left: 5px; padding-right: 5px;">1 byte</td>
 *       <td style="padding-left: 5px; padding-right: 5px;">RAW</td>
 *     </tr>
 *   </table>
 */
final class IntervalEncoder {
  private final ByteBuffer prologueBuffer;
  private final ByteBuffer dataChunkBuffer;
  private final ByteBuffer groupVarintMapBuffer;
  private final int threadCount;
  private int threadIndex = 0;

  private int maskPos = 0;
  private int maskOffset = 0;

  private boolean encoderFinished = false;
  private boolean threadInFlight = false;
  private final LEB128Support leb128Support = new LEB128Support();

  final class ThreadEncoder {
    private final byte[] elements = new byte[8];

    private long runningTimestamp;
    private final long threadId;
    private int intervals = 0;

    private boolean threadFinished = false;

    ThreadEncoder(long threadId) {
      this.threadId = threadId;
      this.runningTimestamp = 0;
    }

    void recordInterval(long from, long till) {
      if (threadFinished) {
        throw new IllegalStateException("Illegal state: threadFinished=" + threadFinished);
      }
      intervals++;
      putLongValue(from - runningTimestamp);
      putLongValue(till - from);
      runningTimestamp = till;
    }

    IntervalEncoder finish() {
      if (threadFinished) {
        throw new IllegalStateException("Illegal state: threadFinished=" + threadFinished);
      }
      leb128Support.putVarint(prologueBuffer, threadId);
      leb128Support.putVarint(prologueBuffer, intervals);
      threadInFlight = false;
      threadFinished = true;
      return IntervalEncoder.this;
    }

    private void putLongValue(long value) {
      int size = leb128Support.longSize(value);

      int base = size - 1;
      for (int i = 0; i < size; i++) {
        elements[base - i] = (byte) (value & 0xff);
        value = value >>> 8;
      }
      dataChunkBuffer.put(elements, 0, size);
      storeLongValueSize(size);
    }

    private void storeLongValueSize(int size) {
      groupVarintMapBuffer.position(maskPos);
      groupVarintMapBuffer.mark();
      int mask = groupVarintMapBuffer.getInt();

      // record the current length
      // need to do 'size - 1' to be able to squeeze the length into 3 bits -> 0 means size of 1
      // etc.
      mask = mask | (((size - 1) & 0x7) << (29 - maskOffset));
      // and rewrite the mask
      groupVarintMapBuffer.reset();
      groupVarintMapBuffer.putInt(mask);
      if ((maskOffset += 3) > 21) {
        maskOffset = 0;
        maskPos += 3;
      }
    }
  }

  /**
   * @param timestampMillis the timestamp in epoch millis
   * @param freqMultiplier number of ticks per 1000ns
   * @param threadCount number of tracked threads
   * @param maxSize max data size
   */
  IntervalEncoder(long timestampMillis, long freqMultiplier, int threadCount, int maxSize) {
    this.threadCount = threadCount;

    this.prologueBuffer =
        ByteBuffer.allocate(
            leb128Support.varintSize(timestampMillis)
                + leb128Support.varintSize(freqMultiplier)
                + leb128Support.varintSize(threadCount)
                + threadCount * 18);
    this.dataChunkBuffer = ByteBuffer.allocate(maxSize * 8 + 4);
    this.groupVarintMapBuffer =
        ByteBuffer.allocate(leb128Support.align((int) (Math.ceil(maxSize / 8d) * 3), 4));

    prologueBuffer.putInt(0); // pre-allocate space for the datachunk offset
    dataChunkBuffer.putInt(0); // pre-allocate space for the group varint map offset
    leb128Support.putVarint(prologueBuffer, timestampMillis);
    leb128Support.putVarint(prologueBuffer, freqMultiplier);
    leb128Support.putVarint(prologueBuffer, threadCount);
  }

  ThreadEncoder startThread(long threadId) {
    if (threadInFlight || threadIndex++ >= threadCount) {
      throw new IllegalStateException(
          "Illegal state: threadInFlight="
              + threadInFlight
              + ", threadIndex="
              + threadIndex
              + ", threadCount="
              + threadCount);
    }
    threadInFlight = true;
    return new ThreadEncoder(threadId);
  }

  ByteBuffer finish() {
    if (encoderFinished || threadInFlight) {
      throw new IllegalStateException(
          "Illegal state: encoderFinished="
              + encoderFinished
              + ", threadInFlight="
              + threadInFlight);
    }
    encoderFinished = true;
    ByteBuffer buffer =
        ByteBuffer.allocate(
            prologueBuffer.position()
                + dataChunkBuffer.position()
                + groupVarintMapBuffer.position());
    prologueBuffer.putInt(0, prologueBuffer.position()); // store the data chunk offset
    dataChunkBuffer.putInt(0, dataChunkBuffer.position()); // store the group varint bitmap offset
    // and now store the parts of the blob
    buffer.put((ByteBuffer) prologueBuffer.flip()); // prologue
    buffer.put((ByteBuffer) dataChunkBuffer.flip()); // data chunk
    buffer.put((ByteBuffer) groupVarintMapBuffer.flip()); // group varint bitmap
    return (ByteBuffer) buffer.flip();
  }
}
