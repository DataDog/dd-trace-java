package datadog.trace.test.agent.decoder.v1.raw;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueType;

/**
 * TraceV1 represents a decoded trace in V1.0 format.
 *
 * <p>Each trace in V1.0 is an array of spans. The string table is shared across all traces in a
 * payload for streaming string decoding.
 */
public class TraceV1 implements DecodedTrace {

  /**
   * Unpacks an array of traces from the unpacker.
   *
   * @param unpacker the message unpacker
   * @param stringTable the shared string table for streaming string decoding
   * @return array of decoded traces
   */
  public static DecodedTrace[] unpackTraces(MessageUnpacker unpacker, List<String> stringTable) {
    try {
      int size = unpacker.unpackArrayHeader();
      if (size < 0) {
        throw new IllegalArgumentException("Negative trace array size " + size);
      }
      DecodedTrace[] traces = new TraceV1[size];
      for (int i = 0; i < size; i++) {
        ValueType valueType = unpacker.getNextFormat().getValueType();
        if (valueType == ValueType.ARRAY) {
          // Legacy expectation: chunks field contains traces directly.
          traces[i] = unpack(unpacker, stringTable);
        } else if (valueType == ValueType.MAP) {
          // Current V1 payload: chunks field contains chunk maps, each with spans in field 4.
          traces[i] = unpackChunk(unpacker, stringTable);
        } else {
          throw new IllegalArgumentException(
              "Expected trace/chunk entry as ARRAY or MAP, got " + valueType);
        }
      }
      return traces;
    } catch (Throwable t) {
      if (t instanceof RuntimeException) {
        throw (RuntimeException) t;
      } else {
        throw new IllegalArgumentException(t);
      }
    }
  }

  /**
   * Unpacks a single trace (array of spans) from the unpacker.
   *
   * @param unpacker the message unpacker
   * @param stringTable the shared string table for streaming string decoding
   * @return the decoded trace
   */
  public static TraceV1 unpack(MessageUnpacker unpacker, List<String> stringTable) {
    return new TraceV1(SpanV1.unpackSpans(unpacker, stringTable));
  }

  private static TraceV1 unpackChunk(MessageUnpacker unpacker, List<String> stringTable)
      throws IOException {
    int fieldCount = unpacker.unpackMapHeader();
    DecodedSpan[] spans = new DecodedSpan[0];

    for (int i = 0; i < fieldCount; i++) {
      int fieldId = unpacker.unpackInt();
      switch (fieldId) {
        case 2:
          // origin (streaming string)
          SpanV1.unpackStreamingString(unpacker, stringTable);
          break;
        case 3:
          // chunk attributes (triplets array)
          skipAttributes(unpacker, stringTable);
          break;
        case 4:
          spans = SpanV1.unpackSpans(unpacker, stringTable);
          break;
        default:
          // numeric or binary fields that don't affect the string table.
          unpacker.skipValue();
          break;
      }
    }

    return new TraceV1(spans);
  }

  private static void skipAttributes(MessageUnpacker unpacker, List<String> stringTable)
      throws IOException {
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
  }

  private final DecodedSpan[] spans;

  private TraceV1(DecodedSpan[] spans) {
    this.spans = spans;
  }

  @Override
  public List<DecodedSpan> getSpans() {
    if (spans.length == 0) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(Arrays.asList(spans));
  }
}
