package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.Trace;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import datadog.trace.bootstrap.instrumentation.traceannotation.MeasuredMethodFilter;
import java.lang.reflect.Method;

public class TraceDecorator extends BaseDecorator {
  public static TraceDecorator DECORATE = new TraceDecorator();

  private static final boolean useLegacyOperationName =
      InstrumenterConfig.get().isLegacyInstrumentationEnabled(true, "trace.annotations");

  private static final CharSequence TRACE = UTF8BytesString.create("trace");

  private static final String DEFAULT_OPERATION_NAME = "trace.annotation";

  @Override
  protected String[] instrumentationNames() {
    // Can't use "trace" because that's used as the general config name:
    return new String[] {"trace-annotation", "trace-config"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return TRACE;
  }

  public boolean useLegacyOperationName() {
    return useLegacyOperationName;
  }

  public AgentSpan startMethodSpan(Method method) {
    CharSequence operationName = null;
    CharSequence resourceName = null;
    boolean measured = false;

    Trace traceAnnotation = method.getAnnotation(Trace.class);
    if (null != traceAnnotation) {
      operationName = traceAnnotation.operationName();
      try {
        resourceName = traceAnnotation.resourceName();
      } catch (Throwable ignore) {
        // dd-trace-api < 0.31.0 on classpath
      }

      try {
        measured = traceAnnotation.measured();
      } catch (Throwable ignore) {
        // dd-trace-api < 1.10.0 on classpath
      }
    }

    if (operationName == null || operationName.length() == 0) {
      if (DECORATE.useLegacyOperationName()) {
        operationName = DEFAULT_OPERATION_NAME;
      } else {
        operationName = DECORATE.spanNameForMethod(method);
      }
    }

    if (resourceName == null || resourceName.length() == 0) {
      resourceName = DECORATE.spanNameForMethod(method);
    }

    AgentSpan span = startSpan(operationName);
    DECORATE.afterStart(span);
    span.setResourceName(resourceName);

    if (measured || MeasuredMethodFilter.filter(method)) {
      span.setMeasured(true);
    }

    return span;
  }
}
