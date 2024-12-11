package datadog.trace.bootstrap.debugger.spanorigin;

import static datadog.trace.bootstrap.debugger.DebuggerContext.captureCodeOrigin;

import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;

public class CodeOriginInfo {
  public static void entry(Method method) {
    if (InstrumenterConfig.get().isCodeOriginEnabled()) {
      captureCodeOrigin(method, true);
    }
  }

  public static void exit(AgentSpan span) {
    if (InstrumenterConfig.get().isCodeOriginEnabled()) {
      String probeId = captureCodeOrigin(false);
      if (span != null) {
        span.getLocalRootSpan().setTag(probeId, span);
      }
    }
  }
}
