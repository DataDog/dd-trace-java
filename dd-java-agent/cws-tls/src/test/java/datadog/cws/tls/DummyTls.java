package datadog.cws.tls;

import datadog.trace.api.DDId;

class DummyTls implements Tls {

  private DDId traceId;
  private DDId spanId;

  @Override
  public void registerSpan(DDId traceId, DDId spanId) {
    this.traceId = traceId;
    this.spanId = spanId;
  }

  @Override
  public DDId getSpanId() {
    return spanId;
  }

  @Override
  public DDId getTraceId() {
    return traceId;
  }
}
