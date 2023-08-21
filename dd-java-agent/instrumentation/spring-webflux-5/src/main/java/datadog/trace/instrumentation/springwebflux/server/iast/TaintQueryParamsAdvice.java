package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.source.WebModule;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.springframework.util.MultiValueMap;

@RequiresRequestContext(RequestContextSlot.IAST)
class TaintQueryParamsAdvice {

  @SuppressWarnings("Duplicates")
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void after(@Advice.Return MultiValueMap<String, String> queryParams) {
    WebModule module = InstrumentationBridge.WEB;
    if (module == null) {
      return;
    }

    module.onParameterNames(queryParams.keySet());

    for (Map.Entry<String, List<String>> e : queryParams.entrySet()) {
      String parameterName = e.getKey();
      for (String parameterValue : e.getValue()) {
        module.onParameterValue(parameterName, parameterValue);
      }
    }
  }
}
