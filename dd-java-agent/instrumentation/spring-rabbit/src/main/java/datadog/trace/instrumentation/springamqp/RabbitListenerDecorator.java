package datadog.trace.instrumentation.springamqp;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class RabbitListenerDecorator extends BaseDecorator {
  public static final RabbitListenerDecorator DECORATE = new RabbitListenerDecorator();

  public static final CharSequence AMQP_CONSUME = UTF8BytesString.create("amqp.consume");
  public static final CharSequence RABBITMQ_AMQP = UTF8BytesString.create("rabbitmq-amqp");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"amqp", "rabbitmq"};
  }

  @Override
  protected CharSequence component() {
    return RABBITMQ_AMQP;
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  public void onConsume(AgentSpan span, String queueName) {
    span.setResourceName("amqp.consume " + normalizeQueueName(queueName));
  }

  private String normalizeQueueName(String queueName) {
    if (queueName == null || queueName.isEmpty()) {
      return "<default>";
    } else if (queueName.startsWith("amq.gen-")) {
      return "<generated>";
    } else {
      return queueName;
    }
  }
}
