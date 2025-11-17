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

/**
 * @see org.springframework.http.HttpHeaders#getFirst(String)
 */
@RequiresRequestContext(RequestContextSlot.IAST)
class TaintHttpHeadersGetFirstAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  public static void after(
      @Advice.This Object self,
      @Advice.Argument(0) String arg,
      @Advice.Return String value,
      @ActiveRequestContext RequestContext reqCtx) {

    PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null || arg == null || value == null) {
      return;
    }
    IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
    module.taintStringIfTainted(ctx, value, self, SourceTypes.REQUEST_HEADER_VALUE, arg);
  }
}
