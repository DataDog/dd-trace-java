package datadog.cws.tls;

import datadog.trace.api.DDId;

public interface Tls {

  public void registerSpan(DDId traceId, DDId spanId);

  public DDId getSpanId();

  public DDId getTraceId();
}
