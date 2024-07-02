package datadog.cws.tls;

import datadog.trace.api.DDTraceId;
import java.math.BigInteger;

class DummyTls implements Tls {

  private DDTraceId traceId;
  private BigInteger spanId;

  @Override
  public void registerSpan(DDTraceId traceId, BigInteger spanId) {
    this.traceId = traceId;
    this.spanId = spanId;
  }

  @Override
  public BigInteger getSpanId() {
    return spanId;
  }

  @Override
  public DDTraceId getTraceId() {
    return traceId;
  }
}
