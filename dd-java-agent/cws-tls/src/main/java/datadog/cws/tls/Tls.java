package datadog.cws.tls;

import datadog.trace.api.DDTraceId;
import java.math.BigInteger;

public interface Tls {

  public void registerSpan(DDTraceId traceId, BigInteger spanId);

  public BigInteger getSpanId();

  public DDTraceId getTraceId();
}
