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
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.springframework.util.MultiValueMap;

@RequiresRequestContext(RequestContextSlot.IAST)
class TaintQueryParamsAdvice {

  @SuppressWarnings("Duplicates")
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
  public static void after(
      @Advice.Return MultiValueMap<String, String> queryParams,
      @ActiveRequestContext RequestContext reqCtx) {
    final PropagationModule prop = InstrumentationBridge.PROPAGATION;
    if (prop == null || queryParams == null || queryParams.isEmpty()) {
      return;
    }

    final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
    for (Map.Entry<String, List<String>> e : queryParams.entrySet()) {
      String name = e.getKey();
      prop.taintString(ctx, name, SourceTypes.REQUEST_PARAMETER_NAME, name);
      for (String value : e.getValue()) {
        prop.taintString(ctx, value, SourceTypes.REQUEST_PARAMETER_VALUE, name);
      }
    }
  }
}
