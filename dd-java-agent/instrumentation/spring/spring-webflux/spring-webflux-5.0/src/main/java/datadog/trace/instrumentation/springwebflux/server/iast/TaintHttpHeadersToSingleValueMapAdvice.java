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
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.springframework.http.HttpHeaders;

/**
 * @see HttpHeaders#toSingleValueMap()
 */
@RequiresRequestContext(RequestContextSlot.IAST)
class TaintHttpHeadersToSingleValueMapAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  public static void after(
      @Advice.This Object self,
      @Advice.Return Map<String, String> values,
      @ActiveRequestContext RequestContext reqCtx) {
    PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null || values == null || values.isEmpty()) {
      return;
    }

    final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
    for (Map.Entry<String, String> e : values.entrySet()) {
      final String name = e.getKey();
      final String value = e.getValue();
      module.taintStringIfTainted(ctx, name, self, SourceTypes.REQUEST_HEADER_NAME, name);
      module.taintStringIfTainted(ctx, value, self, SourceTypes.REQUEST_HEADER_VALUE, name);
    }
  }
}
