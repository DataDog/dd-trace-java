package datadog.trace.bootstrap.debugger.spanorigin;

import static datadog.trace.bootstrap.debugger.DebuggerContext.captureCodeOrigin;
import static java.util.Arrays.stream;

import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import java.util.stream.Collectors;

public class CodeOriginInfo {
  public static void entry(Method method) {
    if (InstrumenterConfig.get().isCodeOriginEnabled()) {
      String signature =
          stream(method.getParameterTypes())
              .map(type -> internalize(type))
              .collect(Collectors.joining("", "(", ")"));
      captureCodeOrigin(signature);
    }
  }

  private static String internalize(Class<?> type) {
    if (type.equals(boolean.class)) {
      return "Z";
    } else if (type.equals(byte.class)) {
      return "B";
    } else if (type.equals(char.class)) {
      return "C";
    } else if (type.equals(double.class)) {
      return "D";
    } else if (type.equals(float.class)) {
      return "F";
    } else if (type.equals(int.class)) {
      return "I";
    } else if (type.equals(long.class)) {
      return "J";
    } else if (type.equals(short.class)) {
      return "S";
    } else {
      return "L" + type.getName().replace('.', '/') + ";";
    }
  }

  public static void exit(AgentSpan span) {
    if (InstrumenterConfig.get().isCodeOriginEnabled()) {
      String probeId = captureCodeOrigin(null);
      if (span != null) {
        span.getLocalRootSpan().setTag(probeId, span);
      }
    }
  }
}
