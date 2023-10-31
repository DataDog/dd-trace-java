package com.datadog.iast;

import static com.datadog.iast.IastTag.ANALYZED;
import static com.datadog.iast.IastTag.SKIPPED;

import com.datadog.iast.overhead.OverheadController;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.HttpRequestEndModule;
import datadog.trace.api.internal.TraceSegment;
import java.util.function.BiFunction;
import javax.annotation.Nonnull;

public class RequestEndedHandler implements BiFunction<RequestContext, IGSpanInfo, Flow<Void>> {

  private final OverheadController overheadController;

  public RequestEndedHandler(@Nonnull final Dependencies dependencies) {
    this.overheadController = dependencies.getOverheadController();
  }

  @Override
  public Flow<Void> apply(final RequestContext requestContext, final IGSpanInfo igSpanInfo) {
    final TraceSegment traceSegment = requestContext.getTraceSegment();
    final IastRequestContext iastCtx = IastRequestContext.get(requestContext);
    if (iastCtx != null) {
      for (HttpRequestEndModule module : requestEndModules()) {
        if (module != null) {
          module.onRequestEnd(iastCtx, igSpanInfo);
        }
      }
      try {
        ANALYZED.setTagTop(traceSegment);
      } finally {
        overheadController.releaseRequest();
      }
    } else {
      SKIPPED.setTagTop(traceSegment);
    }
    return Flow.ResultFlow.empty();
  }

  private HttpRequestEndModule[] requestEndModules() {
    return new HttpRequestEndModule[] {
      InstrumentationBridge.HSTS_MISSING_HEADER_MODULE,
      InstrumentationBridge.X_CONTENT_TYPE_HEADER_MODULE
    };
  }
}
