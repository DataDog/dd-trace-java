package datadog.trace.bootstrap.instrumentation.traceannotation;

import datadog.trace.api.InstrumenterConfig;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class MeasuredMethodFilter {
  public static final MeasuredMethodFilter FILTER = new MeasuredMethodFilter();
  private static Map<String, Set<String>> methodsToMeasure;

  public MeasuredMethodFilter() {
    if (InstrumenterConfig.get().getMeasureMethods().isEmpty()
        || InstrumenterConfig.get().getMeasureMethods() == null) {
      methodsToMeasure = Collections.emptyMap();
    }
    methodsToMeasure =
        TraceAnnotationConfigParser.parse(InstrumenterConfig.get().getMeasureMethods());
  }

  public boolean filter(Method method) {
    String clazz = method.getDeclaringClass().getName();
    return methodsToMeasure.containsKey(clazz)
        && (methodsToMeasure.get(clazz).contains(method.getName()) ||
        methodsToMeasure.get(clazz).contains("*"));
  }
}
