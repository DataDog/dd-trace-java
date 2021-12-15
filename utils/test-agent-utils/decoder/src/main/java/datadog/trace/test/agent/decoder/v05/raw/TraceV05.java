package datadog.trace.test.agent.decoder.v05.raw;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.msgpack.core.MessageUnpacker;

public class TraceV05 implements DecodedTrace {
  public static DecodedTrace[] unpackTraces(MessageUnpacker unpacker, DictionaryV05 dictionary) {
    try {
      int size = unpacker.unpackArrayHeader();
      if (size < 0) {
        throw new IllegalArgumentException("Negative trace array size " + size);
      }
      DecodedTrace[] traces = new TraceV05[size];
      for (int i = 0; i < size; i++) {
        traces[i] = unpack(unpacker, dictionary);
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

  public static TraceV05 unpack(MessageUnpacker unpacker, DictionaryV05 dictionary) {
    return new TraceV05(SpanV05.unpackSpans(unpacker, dictionary));
  }

  private final DecodedSpan[] spans;

  private TraceV05(DecodedSpan[] spans) {
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
