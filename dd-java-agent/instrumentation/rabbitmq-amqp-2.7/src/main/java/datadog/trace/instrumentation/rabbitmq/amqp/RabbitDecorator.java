package datadog.trace.instrumentation.rabbitmq.amqp;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_COMMAND;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_EXCHANGE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_QUEUE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_ROUTING_KEY;

import com.rabbitmq.client.Command;
import com.rabbitmq.client.Envelope;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;

public class RabbitDecorator extends ClientDecorator {

  public static final RabbitDecorator DECORATE = new RabbitDecorator();

  public static final RabbitDecorator PRODUCER_DECORATE =
      new RabbitDecorator() {
        @Override
        protected String spanKind() {
          return Tags.SPAN_KIND_PRODUCER;
        }

        @Override
        protected String spanType() {
          return DDSpanTypes.MESSAGE_PRODUCER;
        }
      };

  public static final RabbitDecorator CONSUMER_DECORATE =
      new RabbitDecorator() {
        @Override
        protected String spanKind() {
          return Tags.SPAN_KIND_CONSUMER;
        }

        @Override
        protected String spanType() {
          return DDSpanTypes.MESSAGE_CONSUMER;
        }
      };

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"amqp", "rabbitmq"};
  }

  @Override
  protected String service() {
    return "rabbitmq";
  }

  @Override
  protected String component() {
    return "rabbitmq-amqp";
  }

  @Override
  protected String spanKind() {
    return Tags.SPAN_KIND_CLIENT;
  }

  @Override
  protected String spanType() {
    return DDSpanTypes.MESSAGE_CLIENT;
  }

  public void onPublish(final AgentSpan span, final String exchange, final String routingKey) {
    final String exchangeName = exchange == null || exchange.isEmpty() ? "<default>" : exchange;
    final String routing =
        routingKey == null || routingKey.isEmpty()
            ? "<all>"
            : routingKey.startsWith("amq.gen-") ? "<generated>" : routingKey;
    span.setTag(DDTags.RESOURCE_NAME, "basic.publish " + exchangeName + " -> " + routing);
    span.setTag(DDTags.SPAN_TYPE, DDSpanTypes.MESSAGE_PRODUCER);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_PRODUCER);
    span.setTag(AMQP_COMMAND, "basic.publish");
    span.setTag(AMQP_EXCHANGE, exchange);
    span.setTag(AMQP_ROUTING_KEY, routingKey);
  }

  public void onGet(final AgentSpan span, final String queue) {
    final String queueName = queue.startsWith("amq.gen-") ? "<generated>" : queue;
    span.setTag(DDTags.RESOURCE_NAME, "basic.get " + queueName);

    span.setTag(AMQP_COMMAND, "basic.get");
    span.setTag(AMQP_QUEUE, queue);
  }

  public void onDeliver(final AgentSpan span, final String queue, final Envelope envelope) {
    String queueName = queue;
    if (queue == null || queue.isEmpty()) {
      queueName = "<default>";
    } else if (queue.startsWith("amq.gen-")) {
      queueName = "<generated>";
    }
    span.setTag(DDTags.RESOURCE_NAME, "basic.deliver " + queueName);
    span.setTag(AMQP_COMMAND, "basic.deliver");

    if (envelope != null) {
      span.setTag(AMQP_EXCHANGE, envelope.getExchange());
      span.setTag(AMQP_ROUTING_KEY, envelope.getRoutingKey());
    }
  }

  public void onCommand(final AgentSpan span, final Command command) {
    final String name = command.getMethod().protocolMethodName();

    if (!name.equals("basic.publish")) {
      // Don't overwrite the name already set.
      span.setTag(DDTags.RESOURCE_NAME, name);
    }
    span.setTag(AMQP_COMMAND, name);
  }
}
