package datadog.trace.instrumentation.opentelemetry.annotations;

import static datadog.trace.api.DDSpanTypes.HTTP_CLIENT;
import static datadog.trace.api.DDSpanTypes.HTTP_SERVER;
import static datadog.trace.api.DDSpanTypes.MESSAGE_CONSUMER;
import static datadog.trace.api.DDSpanTypes.MESSAGE_PRODUCER;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.lang.Math.min;

import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.AsyncResultDecorator;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

public class WithSpanDecorator extends AsyncResultDecorator {
  public static final WithSpanDecorator DECORATE = new WithSpanDecorator();
  private static final String INSTRUMENTATION_NAME = "opentelemetry-annotations";
  private static final CharSequence OPENTELEMETRY = UTF8BytesString.create("opentelemetry");

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

    WithSpan withSpanAnnotation = method.getAnnotation(WithSpan.class);
    if (withSpanAnnotation != null) {
      operationName = withSpanAnnotation.value();
      spanType = convertToSpanType(withSpanAnnotation.kind());
    }

    if (operationName == null || operationName.length() == 0) {
      operationName = DECORATE.spanNameForMethod(method);
    }

    AgentSpan span = startSpan(INSTRUMENTATION_NAME, operationName);
    DECORATE.afterStart(span);

    if (spanType != null) {
      span.setSpanType(spanType);
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
        break;
      }
    }
  }
}
