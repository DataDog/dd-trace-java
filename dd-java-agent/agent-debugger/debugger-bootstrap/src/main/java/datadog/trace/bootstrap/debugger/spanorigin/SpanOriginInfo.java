package datadog.trace.bootstrap.debugger.spanorigin;

import static datadog.trace.bootstrap.debugger.DebuggerContext.captureSnapshot;
import static java.util.Arrays.stream;

import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import java.util.stream.Collectors;

public class SpanOriginInfo {
  public static void entry(AgentSpan span, Method method) {
    if (InstrumenterConfig.get().isSpanOriginEnabled()
        // i hate this too but need it for testing
        || "true".equals(System.getProperty("dd.trace.span.origin.enabled"))) {
      String signature =
          stream(method.getParameterTypes())
              .map(Class::getName)
              .collect(Collectors.joining(",", "(", ")"));
      captureSnapshot(signature);
    }
  }

  public static void exit(AgentSpan span) {
    if (InstrumenterConfig.get().isSpanOriginEnabled()
        // i hate this too but need it for testing
        || "true".equals(System.getProperty("dd.trace.span.origin.enabled"))) {
      span.getLocalRootSpan().setMetaStruct(captureSnapshot(null), span);
    }
  }
}
