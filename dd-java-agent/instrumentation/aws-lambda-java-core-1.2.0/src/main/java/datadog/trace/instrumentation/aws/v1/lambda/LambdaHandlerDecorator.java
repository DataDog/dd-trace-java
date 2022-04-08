package datadog.trace.instrumentation.aws.v1.lambda;

import datadog.trace.api.Function;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.QualifiedClassNameCache;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.opentracing.SpanContext;

public class LambdaHandlerDecorator extends ClientDecorator {

  private static final Logger log = LoggerFactory.getLogger(LambdaHandlerDecorator.class);

  public static final LambdaHandlerDecorator DECORATE = new LambdaHandlerDecorator();

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.HTTP_CLIENT;
  }

  @Override
  protected String[] instrumentationNames() {

    return new String[] {"aws-lambda-component"};
  }

  @Override
  protected CharSequence component() {
    String component = System.getenv("DD_COMPONENT");
    return (null == component) ? "aws-lambda-component" : component;
  }

  @Override
  protected String service() {
    String service = System.getenv("DD_SERVICE");
    return (null == service) ? "aws-lambda-component" : service;
  }

  /** Decorate trace based on service execution metadata. */
  public AgentSpan onServiceExecution(
      final AgentSpan span, final Object serviceExecutor, final String methodName) {
    span.setResourceName("totoResourceName");
    return span;
  }

  /** Annotate the span with the results of the operation. */
  public AgentSpan onResult(final AgentSpan span, Object result) {
    return span;
  }

}

