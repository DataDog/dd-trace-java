package datadog.trace.bootstrap.instrumentation.traceannotation;

import datadog.trace.api.InstrumenterConfig;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

public class MeasuredMethodFilter {
  private static final Map<String, Set<String>> methodsToMeasure;
  private static final boolean filterIsEmpty;

  static {
    String configString = InstrumenterConfig.get().getMeasureMethods();
    methodsToMeasure = TraceAnnotationConfigParser.parse(configString);
    filterIsEmpty = methodsToMeasure.isEmpty();
  }

  public static boolean filter(Method method) {
    if (!filterIsEmpty) {
      String clazz = method.getDeclaringClass().getName();
      Set<String> methods = methodsToMeasure.get(clazz);
      return methods != null && (methods.contains(method.getName()) || methods.contains("*"));
    }
    return false;
  }
}
