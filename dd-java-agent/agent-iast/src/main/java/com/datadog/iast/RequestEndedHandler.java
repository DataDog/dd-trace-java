package com.datadog.iast;

import static com.datadog.iast.IastTag.ANALYZED;
import static com.datadog.iast.IastTag.SKIPPED;

import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.function.BiFunction;

public class RequestEndedHandler implements BiFunction<RequestContext, IGSpanInfo, Flow<Void>> {

  private final OverheadController overheadController;

  public RequestEndedHandler(final OverheadController overheadController) {
    this.overheadController = overheadController;
  }

  @Override
  public Flow<Void> apply(final RequestContext requestContext, final IGSpanInfo igSpanInfo) {
    final IastRequestContext iastRequestContext = getIastRequestContext(requestContext);
    if (iastRequestContext != null) {
      ANALYZED.setTagTop(requestContext.getTraceSegment());
      final TaintedObjects taintedObjects = iastRequestContext.getTaintedObjects();
      if (taintedObjects != null) {
        taintedObjects.release();
      }
      overheadController.releaseRequest();
    } else {
      SKIPPED.setTagTop(requestContext.getTraceSegment());
    }
    return Flow.ResultFlow.empty();
  }

  private static IastRequestContext getIastRequestContext(final RequestContext requestContext) {
    if (requestContext == null) {
      return null;
    }
    return requestContext.getData(RequestContextSlot.IAST);
  }
}
