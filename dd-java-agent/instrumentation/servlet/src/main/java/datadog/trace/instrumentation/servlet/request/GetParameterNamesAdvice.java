package datadog.trace.instrumentation.servlet.request;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.source.WebModule;
import java.util.Enumeration;
import net.bytebuddy.asm.Advice;

@IastAdvice.Source(SourceTypes.REQUEST_PARAMETER_NAME_STRING)
@RequiresRequestContext(RequestContextSlot.IAST)
public class GetParameterNamesAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void getParameterNames(
      @Advice.Return(readOnly = false) Enumeration<Object> enumeration,
      @ActiveRequestContext final RequestContext reqCtx) {
    if (enumeration == null) {
      return;
    }
    final WebModule module = InstrumentationBridge.WEB;
    if (module == null) {
      return;
    }
    final Object iastRequestContext = reqCtx.getData(RequestContextSlot.IAST);
    if (iastRequestContext == null) {
      return;
    }
    enumeration =
        new TaintEnumerable.ParameterNamesEnumerable<>(module, iastRequestContext, enumeration);
  }
}
