package datadog.trace.test.agent.decoder.v1_0.raw;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.msgpack.core.MessageUnpacker;

/**
 * TraceV1_0 represents a decoded trace in V1.0 format.
 *
 * <p>Each trace in V1.0 is an array of spans. The string table is shared across all traces in a
 * payload for streaming string decoding.
 */
public class TraceV1_0 implements DecodedTrace {

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
      DecodedTrace[] traces = new TraceV1_0[size];
      for (int i = 0; i < size; i++) {
        traces[i] = unpack(unpacker, stringTable);
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
  public static TraceV1_0 unpack(MessageUnpacker unpacker, List<String> stringTable) {
    return new TraceV1_0(SpanV1_0.unpackSpans(unpacker, stringTable));
  }

  private final DecodedSpan[] spans;

  private TraceV1_0(DecodedSpan[] spans) {
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
