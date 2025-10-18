package datadog.trace.instrumentation.opentelemetry.annotations;

import static datadog.opentelemetry.shim.trace.OtelConventions.toSpanKindTagValue;
import static datadog.trace.api.DDSpanTypes.HTTP_CLIENT;
import static datadog.trace.api.DDSpanTypes.HTTP_SERVER;
import static datadog.trace.api.DDSpanTypes.MESSAGE_CONSUMER;
import static datadog.trace.api.DDSpanTypes.MESSAGE_PRODUCER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static java.lang.Math.min;

import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.AsyncResultDecorator;
import datadog.trace.util.MethodHandles;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

public class WithSpanDecorator extends AsyncResultDecorator {
  public static final WithSpanDecorator DECORATE = new WithSpanDecorator();
  private static final String INSTRUMENTATION_NAME = "opentelemetry-annotations";
  private static final CharSequence OPENTELEMETRY = UTF8BytesString.create("opentelemetry");
  private static final MethodHandle INHERIT_CONTEXT_MH = maybeGetInheritContextHandle();

  private static MethodHandle maybeGetInheritContextHandle() {
    try {
      return new MethodHandles(WithSpan.class.getClassLoader())
          .method(WithSpan.class, "inheritContext")
          .asType(MethodType.methodType(boolean.class, WithSpan.class));
    } catch (Throwable ignored) {
      // not available before 2.14.0
    }
    return null;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"opentelemetry-annotations", "opentelemetry-annotations-1"};
  }

  @Override
  protected CharSequence spanType() {
    return null; // Will be defined per span from WithSpan annotation parameter
  }

  @Override
  protected CharSequence component() {
    return OPENTELEMETRY;
  }

  public AgentSpan startMethodSpan(Method method) {
    CharSequence operationName = null;
    CharSequence spanType = null;
    SpanKind kind = null;
    boolean inheritContext = true;

    WithSpan withSpanAnnotation = method.getAnnotation(WithSpan.class);
    if (withSpanAnnotation != null) {
      operationName = withSpanAnnotation.value();
      kind = withSpanAnnotation.kind();
      if (INHERIT_CONTEXT_MH != null) {
        try {
          inheritContext = (boolean) INHERIT_CONTEXT_MH.invokeExact(withSpanAnnotation);
        } catch (Throwable ignored) {
        }
      }
    }

    if (operationName == null || operationName.length() == 0) {
      operationName = DECORATE.spanNameForMethod(method);
    }

    AgentTracer.SpanBuilder spanBuilder =
        AgentTracer.get().buildSpan(INSTRUMENTATION_NAME, operationName);

    if (!inheritContext) {
      spanBuilder = spanBuilder.ignoreActiveSpan();
    }
    final AgentSpan span = spanBuilder.start();
    DECORATE.afterStart(span);

    if (kind != null) {
      span.setSpanType(convertToSpanType(kind));
      span.setTag(SPAN_KIND, toSpanKindTagValue(kind));
    }
    if (InstrumenterConfig.get().isMethodMeasured(method)) {
      span.setMeasured(true);
    }

    return span;
  }

  private static String convertToSpanType(SpanKind kind) {
    if (kind == null) {
      return null;
    }
    switch (kind) {
      case SERVER:
        return HTTP_SERVER;
      case CLIENT:
        return HTTP_CLIENT;
      case PRODUCER:
        return MESSAGE_PRODUCER;
      case CONSUMER:
        return MESSAGE_CONSUMER;
      case INTERNAL:
      default:
        return null;
    }
  }

  public void addTagsFromMethodArgs(AgentSpan span, Method method, Object[] args) {
    Parameter[] parameters = method.getParameters();
    for (int parameterIndex = 0;
        parameterIndex < min(parameters.length, args.length);
        parameterIndex++) {
      Parameter parameter = parameters[parameterIndex];
      SpanAttribute annotation = parameter.getAnnotation(SpanAttribute.class);
      if (annotation != null) {
        // Get name from annotation value
        String name = annotation.value();
        // If name is missing, try to get it from parameter description (absent by default)
        if ((name == null || name.isEmpty()) && parameter.isNamePresent()) {
          name = parameter.getName();
        }
        if (name != null) {
          span.setTag(name, args[parameterIndex]);
        }
      }
    }
  }
}
