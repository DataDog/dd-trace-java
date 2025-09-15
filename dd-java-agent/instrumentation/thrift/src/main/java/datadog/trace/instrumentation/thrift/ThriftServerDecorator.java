package datadog.trace.instrumentation.thrift;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.thrift.ExtractAdepter.GETTER;
import static datadog.trace.instrumentation.thrift.ThriftConstants.*;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import java.util.Map;
import java.util.Optional;

public class ThriftServerDecorator extends ThriftBaseDecorator {
  public static final ThriftServerDecorator SERVER_DECORATOR = new ThriftServerDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[]{INSTRUMENTATION_NAME};
  }

  @Override
  protected CharSequence spanType() {
    return THRIFT;
  }

  @Override
  protected CharSequence component() {
    return THRIFT_SERVER_COMPONENT;
  }

  @Override
  public CharSequence spanName() {
    return component();
  }

  public AgentSpan createSpan(Map<String, String> header,AbstractContext context) {
    AgentSpanContext parentContext = extractContextAndGetSpanContext(header, GETTER);
//    AgentSpan span = startSpan(spanName(),parentContext,context.startTime);
    AgentSpan span = startSpan(spanName(),parentContext);
    withMethod(span, context.methodName);
    withResource(span, Optional.ofNullable(context.getOperatorName()).isPresent()?context.getOperatorName():context.methodName);
    if (Optional.ofNullable(context.getArguments()).isPresent()) {
      span.setTag(ThriftConstants.Tags.ARGS, context.getArguments());
    }
    afterStart(span);
    return span;
  }

}
