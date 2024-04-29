package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.instrumentation.trace_annotation.TraceAnnotationsInstrumentation.loadAnnotations;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.span_origin.EntrySpanOriginInstrumentation;
import java.util.Set;

@AutoService(InstrumenterModule.class)
public class TraceAnnotationSpanOriginInstrumentation extends EntrySpanOriginInstrumentation {
  public TraceAnnotationSpanOriginInstrumentation() {
    super("trace-annotation-span-origin");
  }

  @Override
  protected Set<String> getAnnotations() {
    return loadAnnotations();
  }
}
