package datadog.cws.tls;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;

public class NoTls implements Tls {

  public void registerSpan(DDTraceId traceId, DDSpanId spanId) {}

  public DDSpanId getSpanId() {
    return DDSpanId.ZERO;
  }

  public DDTraceId getTraceId() {
    return DDTraceId.ZERO;
  }
}
