package datadog.trace.instrumentation.thrift;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.thrift.TBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.thrift.ThriftConstants.*;

public class ThriftClientDecorator extends ThriftBaseDecorator {
  private static final Logger log = LoggerFactory.getLogger(ThriftClientDecorator.class);
  public static final ThriftClientDecorator CLIENT_DECORATOR = new ThriftClientDecorator();

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
    return THRIFT_CLIENT_COMPONENT;
  }

  @Override
  public CharSequence spanName() {
    return component();
  }

  public AgentSpan createSpan(String operationName, TBase tb) {
    AgentSpan span = startSpan(spanName());
    withMethod(span, operationName);
    withResource(span, operationName);
    withArgs(span,operationName,tb);
    afterStart(span);
    return span;
  }

}
