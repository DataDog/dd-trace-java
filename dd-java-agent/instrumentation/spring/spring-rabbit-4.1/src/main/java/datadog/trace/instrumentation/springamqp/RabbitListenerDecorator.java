package datadog.trace.instrumentation.springamqp;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_QUEUE;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.MessagingClientDecorator;

public class RabbitListenerDecorator extends MessagingClientDecorator {
  public static final RabbitListenerDecorator DECORATE = new RabbitListenerDecorator();

  public static final CharSequence AMQP_CONSUME = UTF8BytesString.create("amqp.consume");
  public static final CharSequence RABBITMQ_AMQP = UTF8BytesString.create("rabbitmq-amqp");

  private static final boolean RABBITMQ_LEGACY_TRACING =
      SpanNaming.instance().namingSchema().allowInferredServices();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"amqp", "rabbitmq"};
  }

  @Override
  protected String service() {
    return SpanNaming.instance()
        .namingSchema()
        .messaging()
        .inboundService("rabbitmq", RABBITMQ_LEGACY_TRACING)
        .get();
  }

  @Override
  protected CharSequence component() {
    return RABBITMQ_AMQP;
  }

  @Override
  protected String spanKind() {
    return Tags.SPAN_KIND_CONSUMER;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.MESSAGE_CONSUMER;
  }

  public void onConsume(AgentSpan span, String queueName) {
    String normalized = normalizeQueueName(queueName);
    span.setResourceName("amqp.consume " + normalized);
    span.setTag("messaging.system", "rabbitmq");
    span.setTag("messaging.destination.name", normalized);
    span.setTag(AMQP_QUEUE, queueName);
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
