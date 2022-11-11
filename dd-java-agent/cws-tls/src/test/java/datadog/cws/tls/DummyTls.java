package datadog.cws.tls;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;

class DummyTls implements Tls {

  private DDTraceId traceId;
  private DDSpanId spanId;

  @Override
  public void registerSpan(DDTraceId traceId, DDSpanId spanId) {
    this.traceId = traceId;
    this.spanId = spanId;
  }

  @Override
  public DDSpanId getSpanId() {
    return spanId;
  }

  @Override
  public DDTraceId getTraceId() {
    return traceId;
  }
}
