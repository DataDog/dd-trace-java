package datadog.trace.common.writer.ddagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessageUnpacker;

/**
 * Shared decode helpers for the TraceMapper payload tests. The V0.4 and V0.5 payloads use different
 * wire formats (V0.4 = inline strings, V0.5 = dictionary-compressed string indices), so the span
 * decoding lives in each test's own {@code PayloadVerifier}; only the format-agnostic pieces live
 * here.
 */
final class PayloadVerifiers {
  private PayloadVerifiers() {}

  /** A serialized empty string round-trips as "", so a null expected value matches "". */
  static void assertEqualsWithNullAsEmpty(CharSequence expected, CharSequence actual) {
    if (expected == null) {
      assertEquals("", actual);
    } else {
      assertEquals(expected.toString(), actual.toString());
    }
  }

  /** Unpacks a msgpack numeric value, matching the encoder's int/long/float/double formats. */
  static Number unpackNumber(MessageUnpacker unpacker, String key) throws IOException {
    MessageFormat format = unpacker.getNextFormat();
    switch (format) {
      case NEGFIXINT:
      case POSFIXINT:
      case INT8:
      case UINT8:
      case INT16:
      case UINT16:
      case INT32:
      case UINT32:
        return unpacker.unpackInt();
      case INT64:
      case UINT64:
        return unpacker.unpackLong();
      case FLOAT32:
        return unpacker.unpackFloat();
      case FLOAT64:
        return unpacker.unpackDouble();
      default:
        fail("Unexpected type in metrics values: " + format + " for key " + key);
        return null; // unreachable
    }
  }

  /**
   * Growable capturing channel the payload verifiers write the mapper output into before decoding.
   * Grows by the source's remaining bytes when needed.
   */
  static final class CapturingChannel implements WritableByteChannel {
    private ByteBuffer captured;

    CapturingChannel(int size) {
      this.captured = ByteBuffer.allocate(size);
    }

    @Override
    public int write(ByteBuffer src) {
      if (captured.remaining() < src.remaining()) {
        ByteBuffer bigger = ByteBuffer.allocate(captured.capacity() + src.remaining());
        captured.flip();
        bigger.put(captured);
        captured = bigger;
        return write(src);
      }
      captured.put(src);
      return src.position();
    }

    /** Flips the buffer and returns it ready for reading. */
    ByteBuffer flipForReading() {
      captured.flip();
      return captured;
    }

    /** Resets the buffer for the next payload. */
    void resetForWriting() {
      captured.position(0);
      captured.limit(captured.capacity());
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void close() {}
  }
}
