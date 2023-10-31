package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.springframework.http.HttpHeaders;

/** @see HttpHeaders#toSingleValueMap() */
class TaintHttpHeadersToSingleValueMapAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  public static void after(@Advice.Return Map<String, String> values) {
    PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null || values == null || values.isEmpty()) {
      return;
    }

    for (Map.Entry<String, String> e : values.entrySet()) {
      final String name = e.getKey();
      final String value = e.getValue();
      module.taint(name, SourceTypes.REQUEST_HEADER_NAME, name);
      module.taint(value, SourceTypes.REQUEST_HEADER_VALUE, name);
    }
  }
}
