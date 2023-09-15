package com.datadog.iast;

import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.function.BiFunction;

public class GrpcRequestMessageHandler implements BiFunction<RequestContext, Object, Flow<Void>> {

  @Override
  public Flow<Void> apply(final RequestContext ctx, final Object o) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null && o != null) {
      final IastRequestContext iastCtx = ctx.getData(RequestContextSlot.IAST);
      module.taintDeeply(iastCtx, SourceTypes.GRPC_BODY, o);
    }
    return Flow.ResultFlow.empty();
  }
}
