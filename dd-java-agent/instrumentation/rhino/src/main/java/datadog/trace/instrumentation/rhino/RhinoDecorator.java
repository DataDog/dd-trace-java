package datadog.trace.instrumentation.rhino;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

public class RhinoDecorator extends BaseDecorator {
  private static final Logger log = LoggerFactory.getLogger(RhinoDecorator.class);
  public static final RhinoDecorator DECORATOR = new RhinoDecorator();

  public static final String INSTRUMENTATION = "rhino";

  @Override
  protected String[] instrumentationNames() {
    return new String[]{INSTRUMENTATION};
  }

  @Override
  protected CharSequence spanType() {
    return INSTRUMENTATION;
  }

  @Override
  protected CharSequence component() {
    return INSTRUMENTATION;
  }

  public AgentSpan createSpan(String operationName,String script) {
    log.debug("--------------------- operationName:{},script:{}",operationName,script);
    AgentSpan span = startSpan(operationName);
    afterStart(span);
    span.setResourceName(operationName);
    span.setTag("script",script);
    return span;
  }

}
