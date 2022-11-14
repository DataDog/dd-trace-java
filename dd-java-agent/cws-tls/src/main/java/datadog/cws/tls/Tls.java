package datadog.cws.tls;

import datadog.trace.api.DDTraceId;

public interface Tls {

  public void registerSpan(DDTraceId traceId, long spanId);

  public long getSpanId();

  public DDTraceId getTraceId();
}
