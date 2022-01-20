package datadog.trace.test.agent.decoder.v04.raw;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.msgpack.core.MessageUnpacker;

public class TraceV04 implements DecodedTrace {
  public static DecodedTrace[] unpackTraces(MessageUnpacker unpacker) {
    try {
      int size = unpacker.unpackArrayHeader();
      if (size < 0) {
        throw new IllegalArgumentException("Negative trace array size " + size);
      }
      DecodedTrace[] traces = new TraceV04[size];
      for (int i = 0; i < size; i++) {
        traces[i] = unpack(unpacker);
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

  public static TraceV04 unpack(MessageUnpacker unpacker) {
    return new TraceV04(SpanV04.unpackSpans(unpacker));
  }

  private final DecodedSpan[] spans;

  private TraceV04(DecodedSpan[] spans) {
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
