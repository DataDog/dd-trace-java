package datadog.trace.test.agent.decoder.json.raw;

import static java.util.Collections.unmodifiableList;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.List;

/** TraceJson is a single trace (an ordered list of {@link SpanJson}) from the JSON trace format. */
public final class TraceJson implements DecodedTrace {
  private final List<DecodedSpan> spans;

  TraceJson(List<DecodedSpan> spans) {
    this.spans = unmodifiableList(spans);
  }

  @Override
  public List<DecodedSpan> getSpans() {
    return this.spans;
  }

  @Override
  public String toString() {
    return this.spans.toString();
  }
}
