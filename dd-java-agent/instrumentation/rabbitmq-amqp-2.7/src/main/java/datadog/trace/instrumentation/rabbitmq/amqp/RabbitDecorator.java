package datadog.trace.instrumentation.rabbitmq.amqp;

import static datadog.trace.api.datastreams.DataStreamsContext.create;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_COMMAND;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_EXCHANGE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_QUEUE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_ROUTING_KEY;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.RECORD_QUEUE_TIME_MS;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Command;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import datadog.trace.api.Config;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.MessagingClientDecorator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class RabbitDecorator extends MessagingClientDecorator {

  public static final CharSequence OPERATION_AMQP_COMMAND = UTF8BytesString.create("amqp.command");
  public static final CharSequence OPERATION_AMQP_INBOUND =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().inboundOperation("amqp"));

  public static final CharSequence OPERATION_AMQP_DELIVER = UTF8BytesString.create("amqp.deliver");

  public static final CharSequence OPERATION_AMQP_OUTBOUND =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().outboundOperation("amqp"));
  public static final CharSequence RABBITMQ_AMQP = UTF8BytesString.create("rabbitmq-amqp");

  public static final boolean RABBITMQ_LEGACY_TRACING =
      SpanNaming.instance().namingSchema().allowInferredServices()
          && Config.get().isLegacyTracingEnabled(true, "rabbit", "rabbitmq");

  public static final boolean TIME_IN_QUEUE_ENABLED =
      Config.get().isTimeInQueueEnabled(!RABBITMQ_LEGACY_TRACING, "rabbit", "rabbitmq");

  public static final RabbitDecorator CLIENT_DECORATE =
      new RabbitDecorator(
          Tags.SPAN_KIND_CLIENT,
          InternalSpanTypes.MESSAGE_CLIENT,
          SpanNaming.instance()
              .namingSchema()
              .messaging()
              .outboundService("rabbitmq", RABBITMQ_LEGACY_TRACING));
  public static final RabbitDecorator PRODUCER_DECORATE =
      new RabbitDecorator(
          Tags.SPAN_KIND_PRODUCER,
          InternalSpanTypes.MESSAGE_PRODUCER,
          SpanNaming.instance()
              .namingSchema()
              .messaging()
              .outboundService("rabbitmq", RABBITMQ_LEGACY_TRACING));
  public static final RabbitDecorator CONSUMER_DECORATE =
      new RabbitDecorator(
          Tags.SPAN_KIND_CONSUMER,
          InternalSpanTypes.MESSAGE_CONSUMER,
          SpanNaming.instance()
              .namingSchema()
              .messaging()
              .inboundService("rabbitmq", RABBITMQ_LEGACY_TRACING));
  public static final RabbitDecorator BROKER_DECORATE =
      new RabbitDecorator(
          Tags.SPAN_KIND_BROKER,
          InternalSpanTypes.MESSAGE_BROKER,
          SpanNaming.instance().namingSchema().messaging().timeInQueueService("rabbitmq"));

  private final String spanKind;
  private final CharSequence spanType;
  private final Supplier<String> serviceNameSupplier;

  public RabbitDecorator(
      String spanKind, CharSequence spanType, Supplier<String> serviceNameSupplier) {
    this.spanKind = spanKind;
    this.spanType = spanType;
    this.serviceNameSupplier = serviceNameSupplier;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"amqp", "rabbitmq"};
  }

  @Override
  protected String service() {
    return serviceNameSupplier.get();
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
    span.setResourceName(buildResourceName("basic.publish", exchangeName, routing));
    span.setTag(AMQP_EXCHANGE, exchange);
    span.setTag(AMQP_ROUTING_KEY, routingKey);
  }

  public void onGet(final AgentSpan span, final String queue) {
    span.setResourceName("basic.get " + normalizeQueueName(queue));
    // This span is created after the command has returned, so we need to set the tag here
    span.setTag(AMQP_COMMAND, "basic.get");
    span.setTag(AMQP_QUEUE, queue);
  }

  public void onDeliver(final AgentSpan span, final String queue, final Envelope envelope) {
    span.setResourceName("basic.deliver " + normalizeQueueName(queue));
    // This span happens after any AMQP commands so we need to set the tag here
    span.setTag(AMQP_COMMAND, "basic.deliver");
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
    span.setTag(AMQP_COMMAND, name);
  }

  public void onTimeInQueue(final AgentSpan span, final String queue, final byte[] body) {
    String normalizedQueueName = normalizeQueueName(queue);
    if (Config.get().isMessageBrokerSplitByDestination()) {
      span.setServiceName(normalizedQueueName, component());
    }
    span.setResourceName("amqp.deliver " + normalizedQueueName);
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

  private String buildResourceName(
      final String opName, final String exchangeName, final String routingKey) {
    // pre-size to the worst case length
    final StringBuilder prefix =
        new StringBuilder(opName.length() + exchangeName.length() + routingKey.length() + 5)
            .append(opName)
            .append(' ')
            .append(exchangeName);
    if (Config.get().isRabbitIncludeRoutingKeyInResource()) {
      prefix.append(" -> ").append(routingKey);
    }
    return prefix.toString();
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
    final Map<String, Object> headers =
        propagate && null != properties ? properties.getHeaders() : null;
    AgentSpanContext parentContext =
        null != headers
            ? extractContextAndGetSpanContext(headers, ContextVisitors.objectValuesMap())
            : null;
    // TODO: check dynamically bound queues -
    // https://github.com/DataDog/dd-trace-java/pull/2955#discussion_r677787875

    if (spanStartMillis == 0) {
      spanStartMillis = System.currentTimeMillis();
    }
    long queueStartMillis = 0;
    if (null != headers && TIME_IN_QUEUE_ENABLED) {
      queueStartMillis = extractTimeInQueueStart(headers);
    }
    long spanStartMicros = TimeUnit.MILLISECONDS.toMicros(spanStartMillis);
    AgentSpan queueSpan = null;
    if (queueStartMillis != 0) {
      queueStartMillis = Math.min(spanStartMillis, queueStartMillis);
      queueSpan =
          startSpan(
              OPERATION_AMQP_DELIVER,
              parentContext,
              TimeUnit.MILLISECONDS.toMicros(queueStartMillis));
      BROKER_DECORATE.afterStart(queueSpan);
      BROKER_DECORATE.onTimeInQueue(queueSpan, queue, body);
      parentContext = queueSpan.context();
      BROKER_DECORATE.beforeFinish(queueSpan);
      // The queueSpan will be finished after the inner span has been activated to ensure that the
      // spans are written out together by the TraceStructureWriter when running in strict mode
    }
    final AgentSpan span = startSpan(OPERATION_AMQP_INBOUND, parentContext, spanStartMicros);

    if (null != body) {
      span.setTag("message.size", body.length);
    }
    // TODO - do we still need both?
    long produceMillis = 0;
    if (null != properties && null != properties.getTimestamp()) {
      // this will be set if the sender sets the timestamp,
      // or if a plugin is installed on the rabbitmq broker
      produceMillis = properties.getTimestamp().getTime();
      span.setTag(RECORD_QUEUE_TIME_MS, Math.max(0L, spanStartMillis - produceMillis));
    }

    if (null != headers) {
      DataStreamsTags tags = create("rabbitmq", INBOUND, queue);
      AgentTracer.get()
          .getDataStreamsMonitoring()
          .setCheckpoint(span, create(tags, produceMillis, 0));
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
      span.finishWithEndToEnd();
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
