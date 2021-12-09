package datadog.trace.instrumentation.rabbitmq.amqp;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_EXCHANGE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_QUEUE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_ROUTING_KEY;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.RECORD_QUEUE_TIME_MS;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Command;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
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
  public static final CharSequence AMQP_DELIVER = UTF8BytesString.create("amqp.deliver");
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
  public static final RabbitDecorator BROKER_DECORATE =
      new RabbitDecorator(Tags.SPAN_KIND_BROKER, InternalSpanTypes.MESSAGE_BROKER, "rabbitmq");

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
    span.setResourceName("basic.get " + normalizeQueueName(queue));
    // This span is created after the command has returned, so we need to set the tag here
    span.setTag(AMQP_COMMAND.toString(), "basic.get");
    span.setTag(AMQP_QUEUE, queue);
  }

  public void onDeliver(final AgentSpan span, final String queue, final Envelope envelope) {
    span.setResourceName("basic.deliver " + normalizeQueueName(queue));
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

  public void onTimeInQueue(final AgentSpan span, final String queue, final byte[] body) {
    span.setResourceName("amqp.deliver " + normalizeQueueName(queue));
    if (null != body) {
      span.setTag("message.size", body.length);
    }
    span.setTag(AMQP_QUEUE, queue);
  }

  private String normalizeQueueName(String queueName) {
    if (queueName == null || queueName.isEmpty()) {
      return "<default>";
    } else if (queueName.startsWith("amq.gen-")) {
      return "<generated>";
    }
    return queueName;
  }

  public TracedDelegatingConsumer wrapConsumer(String queue, Consumer consumer) {
    return new TracedDelegatingConsumer(queue, consumer);
  }

  public static AgentScope startReceivingSpan(
      boolean propagate,
      long spanStartMillis,
      AMQP.BasicProperties properties,
      byte[] body,
      String queue) {
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
    long queueStartMillis = 0;
    if (propagate && null != parentContext && !Config.get().isRabbitLegacyTracingEnabled()) {
      queueStartMillis = extractTimeInQueueStart(headers);
    } else {
      final AgentSpan parent = activeSpan();
      if (null != parent) {
        parentContext = parent.context();
      }
    }
    long spanStartMicros = TimeUnit.MILLISECONDS.toMicros(spanStartMillis);
    AgentSpan queueSpan = null;
    if (queueStartMillis != 0) {
      queueStartMillis = Math.min(spanStartMillis, queueStartMillis);
      queueSpan =
          startSpan(
              AMQP_DELIVER, parentContext, TimeUnit.MILLISECONDS.toMicros(queueStartMillis), false);
      BROKER_DECORATE.afterStart(queueSpan);
      BROKER_DECORATE.onTimeInQueue(queueSpan, queue, body);
      parentContext = queueSpan.context();
      BROKER_DECORATE.beforeFinish(queueSpan);
      // The queueSpan will be finished after the inner span has been activated to ensure that the
      // spans are written out together by the TraceStructureWriter when running in strict mode
    }
    final AgentSpan span;
    if (null != parentContext) {
      span = startSpan(AMQP_COMMAND, parentContext, spanStartMicros);
    } else {
      span = startSpan(AMQP_COMMAND, spanStartMicros);
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
    AgentScope scope = activateSpan(span);
    if (null != queueSpan) {
      queueSpan.finish(spanStartMicros);
    }
    return scope;
  }

  public static void finishReceivingSpan(AgentScope scope) {
    AgentSpan span = scope.span();
    if (CONSUMER_DECORATE.endToEndDurationsEnabled) {
      span.finishEndToEnd();
    } else {
      span.finish();
    }
    scope.close();
  }

  public static final String RABBITMQ_PRODUCED_KEY = "x_datadog_rabbitmq_produced";

  public static void injectTimeInQueueStart(Map<String, Object> headers) {
    long startMillis = System.currentTimeMillis();
    headers.put(RABBITMQ_PRODUCED_KEY, startMillis);
  }

  public static long extractTimeInQueueStart(Map<String, Object> headers) {
    Long startMillis = null == headers ? null : (Long) headers.get(RABBITMQ_PRODUCED_KEY);
    return null == startMillis ? 0 : startMillis;
  }
}
