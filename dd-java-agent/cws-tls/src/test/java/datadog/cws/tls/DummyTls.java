package datadog.cws.tls;

import datadog.trace.api.DDTraceId;

class DummyTls implements Tls {

  private DDTraceId traceId;
  private long spanId;

  @Override
  public void registerSpan(DDTraceId traceId, long spanId) {
    this.traceId = traceId;
    this.spanId = spanId;
  }

  @Override
  public long getSpanId() {
    return spanId;
  }

  @Override
  public DDTraceId getTraceId() {
    return traceId;
  }
}
