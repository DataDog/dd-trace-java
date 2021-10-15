package datadog.cws.tls;

import datadog.trace.api.DDId;

public final class CwsSpan {

  private final DDId traceId;
  private final DDId spanId;

  CwsSpan(DDId traceId, DDId spanId) {
    this.traceId = traceId;
    this.spanId = spanId;
  }

  DDId getTraceId() {
    return traceId;
  }

  DDId getSpanId() {
    return spanId;
  }
}
