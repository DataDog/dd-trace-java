package datadog.trace.test.agent.decoder.v1.raw;

import datadog.trace.test.agent.decoder.DecodedMessage;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueType;

/** MessageV1 decodes V1.0 trace payload format. */
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
        // Full payload format: map with FIELD_CHUNKS containing trace chunks.
        int mapSize = unpacker.unpackMapHeader();

        for (int i = 0; i < mapSize; i++) {
          int fieldId = unpacker.unpackInt();
          if (fieldId == FIELD_CHUNKS) {
            // This is an array of traces
            DecodedTrace[] traceArray = TraceV1.unpackTraces(unpacker, stringTable);
            traces.addAll(Arrays.asList(traceArray));
          } else {
            // Keep string table aligned while skipping known header fields.
            skipPayloadField(unpacker, fieldId, stringTable);
          }
        }
      } else {
        throw new IllegalArgumentException(
            "Expected Map at start of V1.0 payload, got: " + firstType);
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

  private static void skipPayloadField(
      MessageUnpacker unpacker, int fieldId, List<String> stringTable) throws IOException {
    switch (fieldId) {
      case 2:
      case 3:
      case 4:
      case 5:
      case 6:
      case 7:
      case 8:
      case 9:
        SpanV1.unpackStreamingString(unpacker, stringTable);
        break;
      case 10:
        // Header-level attributes: same triplet encoding as span attributes.
        int arraySize = unpacker.unpackArrayHeader();
        if (arraySize % 3 != 0) {
          throw new IllegalArgumentException(
              "Attributes array size must be divisible by 3, got: " + arraySize);
        }
        int tripletCount = arraySize / 3;
        for (int i = 0; i < tripletCount; i++) {
          SpanV1.unpackStreamingString(unpacker, stringTable);
          int valueType = unpacker.unpackInt();
          switch (valueType) {
            case SpanV1.STRING_VALUE_TYPE:
              SpanV1.unpackStreamingString(unpacker, stringTable);
              break;
            case SpanV1.BOOL_VALUE_TYPE:
              unpacker.unpackBoolean();
              break;
            case SpanV1.FLOAT_VALUE_TYPE:
              unpacker.unpackDouble();
              break;
            default:
              unpacker.skipValue();
              break;
          }
        }
        break;
      default:
        unpacker.skipValue();
        break;
    }
  }

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
