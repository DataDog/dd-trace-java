package datadog.cws.tls;

import datadog.trace.api.DDId;

public class CwsNoTls implements CwsTls {

  public void registerSpan(DDId traceId, DDId spanId) {}

  public DDId getSpanId() {
    return DDId.from(0);
  }

  public DDId getTraceId() {
    return DDId.from(0);
  }
}
