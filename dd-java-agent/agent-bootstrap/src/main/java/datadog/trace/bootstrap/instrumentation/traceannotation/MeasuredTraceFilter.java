package datadog.trace.bootstrap.instrumentation.traceannotation;

import datadog.trace.api.InstrumenterConfig;
import java.util.Map;
import java.util.Set;

public class MeasuredTraceFilter {
  public static MeasuredTraceFilter FILTER = new MeasuredTraceFilter();
  private final Map<String, Set<String>> methodsToMeasure;

  public MeasuredTraceFilter() {
    methodsToMeasure =
        TraceAnnotationConfigParser.parse(InstrumenterConfig.get().getMeasureMethods());
  }
}
