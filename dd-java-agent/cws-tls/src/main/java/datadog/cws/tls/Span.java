package datadog.cws.tls;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;

final class Span {

  private final DDTraceId traceId;
  private final DDSpanId spanId;

  Span(DDTraceId traceId, DDSpanId spanId) {
    this.traceId = traceId;
    this.spanId = spanId;
  }

  DDTraceId getTraceId() {
    return traceId;
  }

  DDSpanId getSpanId() {
    return spanId;
  }
}
