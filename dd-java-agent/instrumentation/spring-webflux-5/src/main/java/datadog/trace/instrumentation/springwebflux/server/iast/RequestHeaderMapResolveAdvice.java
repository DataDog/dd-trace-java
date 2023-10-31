package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.springframework.util.MultiValueMap;

public class RequestHeaderMapResolveAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  public static void after(@Advice.Return(typing = Assigner.Typing.DYNAMIC) Map<String, ?> values) {
    PropagationModule prop = InstrumentationBridge.PROPAGATION;
    if (prop == null || values == null || values.isEmpty()) {
      return;
    }

    if (values instanceof MultiValueMap) {
      for (Map.Entry<String, List<String>> e :
          ((MultiValueMap<String, String>) values).entrySet()) {
        final String name = e.getKey();
        prop.taint(name, SourceTypes.REQUEST_HEADER_NAME, name);
        for (String v : e.getValue()) {
          prop.taint(v, SourceTypes.REQUEST_HEADER_VALUE, name);
        }
      }
    } else {
      for (Map.Entry<String, String> e : ((Map<String, String>) values).entrySet()) {
        final String name = e.getKey();
        final String value = e.getValue();
        prop.taint(name, SourceTypes.REQUEST_HEADER_NAME, name);
        prop.taint(value, SourceTypes.REQUEST_HEADER_VALUE, name);
      }
    }
  }
}
