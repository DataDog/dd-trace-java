package datadog.trace.bootstrap.instrumentation.traceannotation;

import datadog.trace.api.InstrumenterConfig;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class MeasuredMethodFilter {
  private static final Map<String, Set<String>> methodsToMeasure;
  private static final boolean filterIsEmpty;

  static {
    String configString = InstrumenterConfig.get().getMeasureMethods();
    if (configString == null || configString.isEmpty()) {
      methodsToMeasure = Collections.emptyMap();
      filterIsEmpty = true;
    } else {
      methodsToMeasure = TraceAnnotationConfigParser.parse(configString);
      filterIsEmpty = methodsToMeasure.isEmpty();
    }
  }

  public static boolean filter(Method method) {
    if (!filterIsEmpty) {
      String clazz = method.getDeclaringClass().getName();
      return methodsToMeasure.containsKey(clazz)
          && (methodsToMeasure.get(clazz).contains(method.getName())
              || methodsToMeasure.get(clazz).contains("*"));
    }
    return false;
  }
}
