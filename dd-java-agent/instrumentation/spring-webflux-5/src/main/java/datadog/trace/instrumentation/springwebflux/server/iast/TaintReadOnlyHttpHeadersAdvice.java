package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

/** @see org.springframework.http.HttpHeaders#get(Object) */
@RequiresRequestContext(RequestContextSlot.IAST)
class TaintReadOnlyHttpHeadersAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  public static void after(
      @Advice.Argument(0) Object headers,
      @Advice.Return Object retValue,
      @ActiveRequestContext RequestContext reqCtx) {

    PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null || retValue == null) {
      return;
    }
    final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
    module.taintObjectIfTainted(ctx, retValue, headers);
  }
}
