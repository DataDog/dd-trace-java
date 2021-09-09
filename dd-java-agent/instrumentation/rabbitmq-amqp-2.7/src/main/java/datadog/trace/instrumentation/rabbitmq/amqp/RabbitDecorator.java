package datadog.trace.instrumentation.rabbitmq.amqp;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_EXCHANGE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_QUEUE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_ROUTING_KEY;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.RECORD_END_TO_END_DURATION_MS;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.RECORD_QUEUE_TIME_MS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Command;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RabbitDecorator extends ClientDecorator {

  public static final CharSequence AMQP_COMMAND = UTF8BytesString.create("amqp.command");
  public static final CharSequence RABBITMQ_AMQP = UTF8BytesString.create("rabbitmq-amqp");

  private static final String LOCAL_SERVICE_NAME =
      Config.get().isRabbitLegacyTracingEnabled() ? "rabbitmq" : Config.get().getServiceName();
  public static final RabbitDecorator CLIENT_DECORATE =
      new RabbitDecorator(
          Tags.SPAN_KIND_CLIENT, InternalSpanTypes.MESSAGE_CLIENT, LOCAL_SERVICE_NAME);
  public static final RabbitDecorator PRODUCER_DECORATE =
      new RabbitDecorator(
          Tags.SPAN_KIND_PRODUCER, InternalSpanTypes.MESSAGE_PRODUCER, LOCAL_SERVICE_NAME);
  public static final RabbitDecorator CONSUMER_DECORATE =
      new RabbitDecorator(
          Tags.SPAN_KIND_CONSUMER, InternalSpanTypes.MESSAGE_CONSUMER, LOCAL_SERVICE_NAME);

  private final String spanKind;
  private final CharSequence spanType;
  private final String serviceName;

  public RabbitDecorator(String spanKind, CharSequence spanType, String serviceName) {
    this.spanKind = spanKind;
    this.spanType = spanType;
    this.serviceName = serviceName;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"amqp", "rabbitmq"};
  }

  @Override
  protected String service() {
    return serviceName;
  }

  @Override
  protected CharSequence component() {
    return RABBITMQ_AMQP;
  }

  @Override
  protected String spanKind() {
    return spanKind;
  }

  @Override
  protected CharSequence spanType() {
    return spanType;
  }

  public void onPublish(final AgentSpan span, final String exchange, final String routingKey) {
    final String exchangeName = exchange == null || exchange.isEmpty() ? "<default>" : exchange;
    final String routing =
        routingKey == null || routingKey.isEmpty()
            ? "<all>"
            : routingKey.startsWith("amq.gen-") ? "<generated>" : routingKey;
    span.setResourceName("basic.publish " + exchangeName + " -> " + routing);
    span.setTag(AMQP_EXCHANGE, exchange);
    span.setTag(AMQP_ROUTING_KEY, routingKey);
  }

  public void onGet(final AgentSpan span, final String queue) {
    final String queueName = queue.startsWith("amq.gen-") ? "<generated>" : queue;
    span.setResourceName("basic.get " + queueName);
    // This span is created after the command has returned, so we need to set the tag here
    span.setTag(AMQP_COMMAND.toString(), "basic.get");
    span.setTag(AMQP_QUEUE, queue);
  }

  public void onDeliver(final AgentSpan span, final String queue, final Envelope envelope) {
    String queueName = queue;
    if (queue == null || queue.isEmpty()) {
      queueName = "<default>";
    } else if (queue.startsWith("amq.gen-")) {
      queueName = "<generated>";
    }
    span.setResourceName("basic.deliver " + queueName);
    // This span happens after any AMQP commands so we need to set the tag here
    span.setTag(AMQP_COMMAND.toString(), "basic.deliver");
    if (envelope != null) {
      span.setTag(AMQP_EXCHANGE, envelope.getExchange());
      span.setTag(AMQP_ROUTING_KEY, envelope.getRoutingKey());
    }
  }

  public void onCommand(final AgentSpan span, final Command command) {
    final String name = command.getMethod().protocolMethodName();

    // Don't overwrite the name already set by onPublish
    if (!name.equals("basic.publish")) {
      span.setResourceName(name);
    }
    span.setTag(AMQP_COMMAND.toString(), name);
  }

  public TracedDelegatingConsumer wrapConsumer(String queue, Consumer consumer) {
    return new TracedDelegatingConsumer(queue, consumer);
  }

  public static AgentSpan startReceivingSpan(
      boolean propagate, long spanStartMillis, AMQP.BasicProperties properties, byte[] body) {
    final Map<String, Object> headers = null != properties ? properties.getHeaders() : null;
    AgentSpan.Context parentContext =
        (null == headers || !propagate)
            ? null
            : propagate().extract(headers, ContextVisitors.objectValuesMap());
    // TODO: check dynamically bound queues -
    // https://github.com/DataDog/dd-trace-java/pull/2955#discussion_r677787875

    if (spanStartMillis == 0) {
      spanStartMillis = System.currentTimeMillis();
    }
    if (null == parentContext) {
      final AgentSpan parent = activeSpan();
      if (null != parent) {
        parentContext = parent.context();
      }
    }
    final AgentSpan span;
    if (null != parentContext) {
      span =
          startSpan(AMQP_COMMAND, parentContext, TimeUnit.MILLISECONDS.toMicros(spanStartMillis));
    } else {
      span = startSpan(AMQP_COMMAND, TimeUnit.MILLISECONDS.toMicros(spanStartMillis));
    }
    if (null != body) {
      span.setTag("message.size", body.length);
    }
    // TODO - do we still need both?
    if (null != properties && null != properties.getTimestamp()) {
      // this will be set if the sender sets the timestamp,
      // or if a plugin is installed on the rabbitmq broker
      long produceMillis = properties.getTimestamp().getTime();
      span.setTag(RECORD_QUEUE_TIME_MS, Math.max(0L, spanStartMillis - produceMillis));
    }
    CONSUMER_DECORATE.afterStart(span);
    return span;
  }

  public static void finishReceivingSpan(AgentSpan span) {
    if (CONSUMER_DECORATE.endToEndDurationsEnabled) {
      long now = System.currentTimeMillis();
      String traceStartTime = span.getBaggageItem(DDTags.TRACE_START_TIME);
      if (null != traceStartTime) {
        // not being defensive here because we own the lifecycle of this value
        span.setTag(
            RECORD_END_TO_END_DURATION_MS, Math.max(0L, now - Long.parseLong(traceStartTime)));
      }
      span.finish(MILLISECONDS.toMicros(now));
    } else {
      span.finish();
    }
  }
}
