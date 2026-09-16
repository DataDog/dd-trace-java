package datadog.trace.core.otlp.common;

import static datadog.trace.core.otlp.common.OtlpCommonProto.LEN_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.sizeVarInt;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeVarInt;
import static datadog.trace.core.otlp.common.OtlpPayload.PROTOBUF_CONTENT_TYPE;
import static datadog.trace.util.BitUtils.nextPowerOfTwo;

import datadog.communication.serialization.GrowableBuffer;
import java.nio.ByteBuffer;

/**
 * Growable buffer optimized for prepending protobuf messages. This buffer doesn't have a bounded
 * length, and grows linearly. It should only be used to serialize bounded data structures.
 *
 * <p>Messages appear in the final payload in reverse insertion order.
 *
 * @see GrowableBuffer
 */
public final class OtlpProtoBuffer {
  // hard limit to avoid unbounded buffering; matches OTLP spec's recommended default
  public static final int MAX_CAPACITY_BYTES = 64 << 20; // 64 MiB

  private final int initialCapacity;
  private ByteBuffer buffer;
  private int remaining;

  public OtlpProtoBuffer(int requiredCapacity) {
    this.initialCapacity = nextPowerOfTwo(requiredCapacity);
    if (this.initialCapacity > MAX_CAPACITY_BYTES) {
      throw new IllegalArgumentException(
          "OTLP payload initial capacity of "
              + this.initialCapacity
              + " bytes exceeds maximum buffer size of "
              + MAX_CAPACITY_BYTES
              + " bytes");
    }
    this.buffer = ByteBuffer.allocate(initialCapacity);
    this.remaining = initialCapacity;
  }

  /**
   * Records a self-contained protobuf message.
   *
   * @param buf buffer containing the message body
   * @param fieldNum field number of the message
   * @return overall size of the message in bytes
   */
  public int recordMessage(GrowableBuffer buf, int fieldNum) {
    return recordMessage(buf, fieldNum, 0);
  }

  /**
   * Records a protobuf message that has zero or more nested elements already recorded.
   *
   * @param buf buffer containing the message header
   * @param fieldNum field number of the message
   * @param bytesSoFar nested element bytes recorded so far
   * @return overall size of the message in bytes
   */
  public int recordMessage(GrowableBuffer buf, int fieldNum, int bytesSoFar) {
    try {
      ByteBuffer message = buf.flip();
      // calculate space needed to encode message, its total length, and the tag
      int messageSize = message.remaining();
      int length = messageSize + bytesSoFar;
      int tag = fieldNum << 3 | LEN_WIRE_TYPE;
      int numBytes = sizeVarInt(tag) + sizeVarInt(length) + messageSize;
      // grow the buffer to fit the incoming content
      checkCapacity(numBytes);
      remaining -= numBytes;
      // reposition so we can write the encoded message
      buffer.position(remaining);
      // write the usual prelude
      writeVarInt(buffer, tag);
      writeVarInt(buffer, length);
      // write the primary message
      buffer.put(message);
      // no need to reset position here; it's always reset before any write/read
      return numBytes + bytesSoFar;
    } finally {
      buf.reset();
    }
  }

  /**
   * Records a previously cached protobuf message.
   *
   * @param bytes cached bytes containing the message header
   * @return overall size of the message in bytes
   */
  public int recordMessage(byte[] bytes) {
    // grow the buffer to fit the incoming content
    int numBytes = bytes.length;
    checkCapacity(numBytes);
    remaining -= numBytes;
    // reposition so we can write the cached message
    buffer.position(remaining);
    buffer.put(bytes);
    // no need to reset position here; it's always reset before any write/read
    return numBytes;
  }

  /** Flips the buffer, returning the protobuf encoded content for reading. */
  public ByteBuffer flip() {
    buffer.position(remaining);
    return buffer;
  }

  /** Returns the number of bytes currently recorded in the buffer. */
  public int sizeInBytes() {
    return buffer.capacity() - remaining;
  }

  /**
   * Returns an {@link OtlpPayload} containing the protobuf encoded content.
   *
   * <p>This payload is only valid for the calling thread until the next collection.
   */
  public OtlpPayload toPayload() {
    return new OtlpPayload(flip(), PROTOBUF_CONTENT_TYPE);
  }

  /**
   * Resets the buffer in anticipation of the next collection cycle.
   *
   * <p>This does not affect the active payload, which remains valid until the next collection.
   */
  public void reset() {
    if (buffer.capacity() > initialCapacity) {
      buffer = ByteBuffer.allocate(initialCapacity);
    }
    remaining = buffer.capacity();
  }

  /** Grows the buffer to ensure the required number of bytes can be prepended. */
  private void checkCapacity(int required) {
    if (remaining < required) {
      ByteBuffer oldBuffer = flip();
      int oldSize = oldBuffer.remaining();
      // round up to next multiple of initialCapacity that can accommodate required
      // (uses long arithmetic so overflow can be detected before allocating)
      long newSize = ((long) oldSize + required + initialCapacity - 1) & -initialCapacity;
      if (newSize > MAX_CAPACITY_BYTES) {
        throw new IllegalStateException(
            "OTLP payload exceeds maximum buffer size of "
                + MAX_CAPACITY_BYTES
                + " bytes: "
                + oldSize
                + " bytes buffered, "
                + required
                + " more requested");
      }
      ByteBuffer newBuffer = ByteBuffer.allocate((int) newSize);
      // copy over old content so it stays at the far end
      remaining = (int) newSize - oldSize;
      newBuffer.position(remaining);
      newBuffer.put(oldBuffer);
      buffer = newBuffer;
    }
  }
}
