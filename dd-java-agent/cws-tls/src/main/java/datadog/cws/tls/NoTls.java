package datadog.cws.tls;

import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;

public class NoTls implements Tls {

  public void registerSpan(DDTraceId traceId, long spanId) {}

  public long getSpanId() {
    return DDSpanId.ZERO;
  }

  public DDTraceId getTraceId() {
    return DD128bTraceId.ZERO;
  }
}
