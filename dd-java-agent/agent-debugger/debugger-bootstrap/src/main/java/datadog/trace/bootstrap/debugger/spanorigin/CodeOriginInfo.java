package datadog.trace.bootstrap.debugger.spanorigin;

import static datadog.trace.bootstrap.debugger.DebuggerContext.captureCodeOrigin;
import static java.util.Arrays.stream;

import datadog.trace.api.InstrumenterConfig;
import java.lang.reflect.Method;
import java.util.stream.Collectors;

public class CodeOriginInfo {
  public static void entry(Method method) {
    if (InstrumenterConfig.get().isCodeOriginEnabled()) {
      String signature =
          stream(method.getParameterTypes())
              .map(Class::getName)
              .collect(Collectors.joining(",", "(", ")"));
      captureCodeOrigin(signature);
    }
  }

  public static void exit() {
    if (InstrumenterConfig.get().isCodeOriginEnabled()) {
      captureCodeOrigin(null);
    }
  }
}
