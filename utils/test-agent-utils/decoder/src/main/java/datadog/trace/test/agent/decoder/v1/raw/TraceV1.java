package datadog.trace.test.agent.decoder.v1.raw;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.msgpack.core.MessageUnpacker;

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
        traces[i] = unpackChunk(unpacker, stringTable);
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

  private static TraceV1 unpackChunk(MessageUnpacker unpacker, List<String> stringTable)
      throws IOException {
    int fieldCount = unpacker.unpackMapHeader();
    DecodedSpan[] spans = new DecodedSpan[0];
    Integer samplingPriority = null;
    long traceId = 0;

    for (int i = 0; i < fieldCount; i++) {
      int fieldId = unpacker.unpackInt();
      switch (fieldId) {
        case 1:
          // chunk sampling priority
          samplingPriority = unpacker.unpackInt();
          break;
        case 2:
          // origin (streaming string)
          SpanV1.unpackStreamingString(unpacker, stringTable);
          break;
        case 3:
          // chunk attributes (triplets array)
          SpanV1.skipAttributes(unpacker, stringTable);
          break;
        case 4:
          spans = SpanV1.unpackSpans(unpacker, stringTable, traceId);
          break;
        case 6:
          traceId = unpackTraceId(unpacker);
          break;
        default:
          // numeric or binary fields that don't affect the string table.
          unpacker.skipValue();
          break;
      }
    }

    return new TraceV1(withChunkFields(spans, traceId, samplingPriority), samplingPriority);
  }

  private static long unpackTraceId(MessageUnpacker unpacker) throws IOException {
    int payloadSize = unpacker.unpackBinaryHeader();
    byte[] payload = unpacker.readPayload(payloadSize);
    if (payloadSize < Long.BYTES) {
      return 0;
    }
    long id = 0;
    for (int i = payloadSize - Long.BYTES; i < payloadSize; i++) {
      id = (id << 8) | (payload[i] & 0xffL);
    }
    return id;
  }

  private static DecodedSpan[] withChunkFields(
      DecodedSpan[] spans, long traceId, Integer samplingPriority) {
    if (spans.length == 0) {
      return spans;
    }
    final long normalizedTraceId = traceId;
    final Integer normalizedPriority = samplingPriority;
    DecodedSpan[] updated = new DecodedSpan[spans.length];
    for (int i = 0; i < spans.length; i++) {
      DecodedSpan span = spans[i];
      final Map<String, Number> metrics = new HashMap<>(span.getMetrics());
      if (normalizedPriority != null
          && span.getParentId() == 0
          && !metrics.containsKey("_sampling_priority_v1")) {
        metrics.put("_sampling_priority_v1", normalizedPriority);
      }
      updated[i] =
          new SpanV1(
              span.getService(),
              span.getName(),
              span.getResource(),
              normalizedTraceId == 0 ? span.getTraceId() : normalizedTraceId,
              span.getSpanId(),
              span.getParentId(),
              span.getStart(),
              span.getDuration(),
              span.getError(),
              span.getType(),
              metrics,
              span.getMeta(),
              span.getMetaStruct());
    }
    return updated;
  }

  private final DecodedSpan[] spans;
  private final Integer chunkSamplingPriority;

  private TraceV1(DecodedSpan[] spans, Integer chunkSamplingPriority) {
    this.spans = spans;
    this.chunkSamplingPriority = chunkSamplingPriority;
  }

  @Override
  public List<DecodedSpan> getSpans() {
    if (spans.length == 0) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(Arrays.asList(spans));
  }

  @Override
  public Integer getSamplingPriority() {
    return chunkSamplingPriority;
  }
}
