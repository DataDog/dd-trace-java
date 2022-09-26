package com.datadog.iast;

import com.datadog.iast.overhead.OverheadController;
import datadog.trace.api.TraceSegment;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;

public class RequestEndedHandler implements BiFunction<RequestContext, IGSpanInfo, Flow<Void>> {

  private final OverheadController overheadController;

  public RequestEndedHandler(final OverheadController overheadController) {
    this.overheadController = overheadController;
  }

  @Override
  public Flow<Void> apply(final RequestContext requestContext, final IGSpanInfo igSpanInfo) {
    if (requestContext != null && requestContext.getData(RequestContextSlot.IAST) != null) {
      final TraceSegment traceSeg = requestContext.getTraceSegment();
      if (traceSeg != null) {
        traceSeg.setTagTop("_dd.iast.enabled", 1);
      }
      overheadController.releaseRequest();
    }
    return Flow.ResultFlow.empty();
  }
}
