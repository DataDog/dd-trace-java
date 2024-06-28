package datadog.cws.tls;

import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDTraceId;
import java.math.BigInteger;

public class NoTls implements Tls {

  public void registerSpan(DDTraceId traceId, BigInteger spanId) {}

  public BigInteger getSpanId() {
    return BigInteger.ZERO;
  }

  public DDTraceId getTraceId() {
    return DD128bTraceId.ZERO;
  }
}
