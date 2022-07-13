package datadog.trace.api.gateway;

import datadog.trace.api.TraceSegment;

public class RequestContextHolder implements RequestContext<Object> {

  private final Object appsec;
  private final Object iast;

  public RequestContextHolder(
      final RequestContext<Object> parent, final Object appsec, final Object iast) {
    this(appsec == null ? parent.getData() : appsec, iast == null ? parent.getIastContext() : iast);
  }

  public RequestContextHolder(final Object appsec, final Object iast) {
    this.appsec = appsec;
    this.iast = iast;
  }

  @Override
  public Object getData() {
    return appsec;
  }

  @Override
  public Object getIastContext() {
    return iast;
  }

  @Override
  public TraceSegment getTraceSegment() {
    return null;
  }
}
