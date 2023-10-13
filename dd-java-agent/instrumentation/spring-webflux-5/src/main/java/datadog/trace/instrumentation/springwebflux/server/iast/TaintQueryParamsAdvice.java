package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.source.WebModule;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.springframework.util.MultiValueMap;

@RequiresRequestContext(RequestContextSlot.IAST)
class TaintQueryParamsAdvice {

  @SuppressWarnings("Duplicates")
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
  public static void after(@Advice.Return MultiValueMap<String, String> queryParams) {
    final WebModule web = InstrumentationBridge.WEB;
    final PropagationModule prop = InstrumentationBridge.PROPAGATION;
    if (web == null || prop == null) {
      return;
    }

    web.onParameterNames(queryParams.keySet());

    for (Map.Entry<String, List<String>> e : queryParams.entrySet()) {
      String name = e.getKey();
      for (String value : e.getValue()) {
        prop.taint(SourceTypes.REQUEST_PARAMETER_VALUE, name, value);
      }
    }
  }
}
