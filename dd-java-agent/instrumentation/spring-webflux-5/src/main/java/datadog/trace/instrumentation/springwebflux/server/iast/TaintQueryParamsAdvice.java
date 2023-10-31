package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.springframework.util.MultiValueMap;

class TaintQueryParamsAdvice {

  @SuppressWarnings("Duplicates")
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
  public static void after(@Advice.Return MultiValueMap<String, String> queryParams) {
    final PropagationModule prop = InstrumentationBridge.PROPAGATION;
    if (prop == null || queryParams == null || queryParams.isEmpty()) {
      return;
    }

    for (Map.Entry<String, List<String>> e : queryParams.entrySet()) {
      String name = e.getKey();
      prop.taint(name, SourceTypes.REQUEST_PARAMETER_NAME, name);
      for (String value : e.getValue()) {
        prop.taint(value, SourceTypes.REQUEST_PARAMETER_VALUE, name);
      }
    }
  }
}
