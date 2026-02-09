package datadog.trace.test.agent.decoder.v1.raw;

import datadog.trace.test.agent.decoder.DecodedMessage;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueType;

/**
 * MessageV1 decodes V1.0 trace payload format.
 *
 * <p>The V1.0 raw buffer format (from FlushingBuffer.dump()) is a concatenation of traces, where
 * each trace is an array of spans:
 *
 * <pre>
 * [span1, span2, ...]    // trace 1 (array of spans)
 * [span3, span4, ...]    // trace 2 (array of spans)
 * ...
 * </pre>
 *
 * <p>Note: The dump() may include padding (zeros) after the actual data.
 *
 * <p>The full payload format wraps this in a map with field 11 (FIELD_CHUNKS).
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Integer field IDs instead of string keys
 *   <li>Streaming string encoding (string table)
 *   <li>Attributes as flat array of triplets (key, type, value)
 * </ul>
 */
public class MessageV1 implements DecodedMessage {
  // Tracer Payload field IDs
  static final int FIELD_CHUNKS = 11;

  public static MessageV1 unpack(ByteBuffer buffer) {
    return unpack(MessagePack.DEFAULT_UNPACKER_CONFIG.newUnpacker(buffer));
  }

  public static MessageV1 unpack(byte[] buffer) {
    return unpack(MessagePack.DEFAULT_UNPACKER_CONFIG.newUnpacker(buffer));
  }

  static MessageV1 unpack(MessageUnpacker unpacker) {
    try {
      // Initialize string table for streaming string decoding
      // Index 0 is reserved for empty string
      List<String> stringTable = new ArrayList<>();
      stringTable.add("");

      // Check what format we have
      if (!unpacker.hasNext()) {
        return new MessageV1(new DecodedTrace[0]);
      }

      ValueType firstType = unpacker.getNextFormat().getValueType();
      List<DecodedTrace> traces = new ArrayList<>();

      if (firstType == ValueType.MAP) {
        // Full payload format: map with FIELD_CHUNKS containing traces
        int mapSize = unpacker.unpackMapHeader();

        for (int i = 0; i < mapSize; i++) {
          int fieldId = unpacker.unpackInt();
          if (fieldId == FIELD_CHUNKS) {
            // This is an array of traces
            DecodedTrace[] traceArray = TraceV1.unpackTraces(unpacker, stringTable);
            traces.addAll(Arrays.asList(traceArray));
          } else {
            // Skip unknown fields
            unpacker.skipValue();
          }
        }
      } else if (firstType == ValueType.ARRAY) {
        // Raw buffer format: concatenated traces (each trace is an array of spans)
        // Read traces one by one until we exhaust the buffer or hit padding
        while (unpacker.hasNext()) {
          MessageFormat format = unpacker.getNextFormat();
          ValueType valueType = format.getValueType();

          // Stop if we hit padding (zeros or other non-array values)
          if (valueType != ValueType.ARRAY) {
            break;
          }

          DecodedTrace trace = TraceV1.unpack(unpacker, stringTable);
          traces.add(trace);
        }
      } else {
        throw new IllegalArgumentException(
            "Expected Map or Array at start of V1.0 payload, got: " + firstType);
      }

      return new MessageV1(traces.toArray(new DecodedTrace[0]));
    } catch (IOException e) {
      throw new IllegalArgumentException("Failed to decode V1.0 message", e);
    } catch (Throwable t) {
      if (t instanceof RuntimeException) {
        throw (RuntimeException) t;
      } else {
        throw new IllegalArgumentException(t);
      }
    }
  }

  private final DecodedTrace[] traces;

  private MessageV1(DecodedTrace[] traces) {
    this.traces = traces;
  }

  @Override
  public List<DecodedTrace> getTraces() {
    if (traces.length == 0) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(Arrays.asList(traces));
  }
}
