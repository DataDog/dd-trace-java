package datadog.trace.instrumentation.disruptor;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;

public class DisruptorDecorator extends BaseDecorator {
  public static final DisruptorDecorator DECORATE = new DisruptorDecorator();

  private static final Logger log = LoggerFactory.getLogger(DisruptorDecorator.class);
  public static final CharSequence DISRUPTOR = UTF8BytesString.create("disruptor");
  @Override
  protected String[] instrumentationNames() {
    return new String[]{"disruptor"};
  }

  @Override
  protected CharSequence spanType() {
    return DISRUPTOR;
  }

  @Override
  protected CharSequence component() {
    return DISRUPTOR;
  }


  public AgentScope start() {
    AgentSpan span = startSpan("disruptor/publish");
    return activateSpan(span);
  }

  public AgentScope startBySpan(AgentSpan parent) {
    AgentSpan span = startSpan("disruptor/consumer",parent.context());
    return activateSpan(span);
  }
}
