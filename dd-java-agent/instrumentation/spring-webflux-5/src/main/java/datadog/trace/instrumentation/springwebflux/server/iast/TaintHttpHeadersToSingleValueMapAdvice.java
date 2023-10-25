package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.source.WebModule;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.springframework.http.HttpHeaders;

/** @see HttpHeaders#toSingleValueMap() */
@RequiresRequestContext(RequestContextSlot.IAST)
class TaintHttpHeadersToSingleValueMapAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  public static void after(@Advice.Return Map<String, String> values) {
    PropagationModule propModule = InstrumentationBridge.PROPAGATION;
    WebModule module = InstrumentationBridge.WEB;
    if (module == null || propModule == null || values == null) {
      return;
    }

    module.onHeaderNames(values.keySet());
    for (Map.Entry<String, String> e : values.entrySet()) {
      propModule.taint(SourceTypes.REQUEST_HEADER_VALUE, e.getKey(), e.getValue());
    }
  }
}
