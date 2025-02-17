package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.Trace;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.AsyncResultDecorator;
import java.lang.reflect.Method;

public class TraceDecorator extends AsyncResultDecorator {
  public static TraceDecorator DECORATE = new TraceDecorator();
  private static final String INSTRUMENTATION_NAME = "trace-annotation";

  private static final boolean USE_LEGACY_OPERATION_NAME =
      InstrumenterConfig.get().isLegacyInstrumentationEnabled(true, "trace.annotations");

  private static final boolean ASYNC_SUPPORT = InstrumenterConfig.get().isTraceAnnotationAsync();

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
    return USE_LEGACY_OPERATION_NAME;
  }

  public AgentSpan startMethodSpan(Method method) {
    CharSequence operationName = null;
    CharSequence resourceName = null;
    boolean measured = false;
    boolean noParent = false;

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

      try {
        noParent = traceAnnotation.noParent();
      } catch (Throwable ignore) {
        // dd-trace-api < 1.22.0 on classpath
      }
    }

    if (operationName == null || operationName.length() == 0) {
      if (useLegacyOperationName()) {
        operationName = DEFAULT_OPERATION_NAME;
      } else {
        operationName = spanNameForMethod(method);
      }
    }

    if (resourceName == null || resourceName.length() == 0) {
      resourceName = spanNameForMethod(method);
    }

    AgentSpan span =
        noParent
            ? startSpan(INSTRUMENTATION_NAME, operationName, null)
            : startSpan(INSTRUMENTATION_NAME, operationName);

    afterStart(span);
    span.setResourceName(resourceName);

    if (measured || InstrumenterConfig.get().isMethodMeasured(method)) {
      span.setMeasured(true);
    }

    return span;
  }

  @Override
  public Object wrapAsyncResultOrFinishSpan(
      Object result, Class<?> methodReturnType, AgentSpan span) {
    if (ASYNC_SUPPORT) {
      return super.wrapAsyncResultOrFinishSpan(result, methodReturnType, span);
    } else {
      span.finish();
      return result;
    }
  }
}
