package datadog.cws.tls;

import datadog.trace.api.DDTraceId;

final class Span {

  private final DDTraceId traceId;
  private final long spanId;

  Span(DDTraceId traceId, long spanId) {
    this.traceId = traceId;
    this.spanId = spanId;
  }

  DDTraceId getTraceId() {
    return traceId;
  }

  long getSpanId() {
    return spanId;
  }
}
