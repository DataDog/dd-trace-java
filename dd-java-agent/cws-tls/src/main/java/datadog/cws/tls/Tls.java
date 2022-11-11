package datadog.cws.tls;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;

public interface Tls {

  public void registerSpan(DDTraceId traceId, DDSpanId spanId);

  public DDSpanId getSpanId();

  public DDTraceId getTraceId();
}
