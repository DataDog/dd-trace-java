package datadog.cws.tls;

import datadog.trace.api.DDTraceId;
import java.math.BigInteger;

final class Span {

  private final DDTraceId traceId;
  private final BigInteger spanId;

  Span(DDTraceId traceId, BigInteger spanId) {
    this.traceId = traceId;
    this.spanId = spanId;
  }

  DDTraceId getTraceId() {
    return traceId;
  }

  BigInteger getSpanId() {
    return spanId;
  }
}
