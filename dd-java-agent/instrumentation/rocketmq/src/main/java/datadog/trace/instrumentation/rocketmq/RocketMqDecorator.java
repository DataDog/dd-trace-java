package datadog.trace.instrumentation.rocketmq;

import datadog.context.propagation.Propagators;
import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.*;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import org.apache.rocketmq.client.hook.ConsumeMessageContext;
import org.apache.rocketmq.client.hook.SendMessageContext;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.function.Supplier;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.rocketmq.TextMapExtractAdapter.GETTER;
import static datadog.trace.instrumentation.rocketmq.TextMapInjectAdapter.SETTER;

public class RocketMqDecorator extends ClientDecorator {
  private static final Logger log = LoggerFactory.getLogger(RocketMqDecorator.class);
  private static final String ROCKETMQ = "rocketmq";
  public static final CharSequence ROCKETMQ_NAME = UTF8BytesString.create("rocketmq");
  private static final String BROKER_HOST = "bornHost";
  private static final String BROKER_ADDR = "bornAddr";
  private static final String BROKER_NAME = "brokerName";
  private static final String TOPIC = "topic";
  private static final String MESSAGING_ROCKETMQ_TAGS = "messaging.rocketmq.tags";
  private static final String MESSAGING_ROCKETMQ_BROKER_ADDRESS =
      "messaging.rocketmq.broker_address";
  private static final String MESSAGING_ROCKETMQ_SEND_RESULT = "messaging.rocketmq.send_result";
  private static final String MESSAGING_ROCKETMQ_QUEUE_ID = "messaging.rocketmq.queue_id";
  private static final String MESSAGING_ID = "messaging.id";
  private static final String MESSAGING_ROCKETMQ_QUEUE_OFFSET = "messaging.rocketmq.queue_offset";
  public boolean ignore = Config.get().getRocketMQConsumeIgnore();
  private final String spanKind;
  private final CharSequence spanType;
  private final Supplier<String> serviceNameSupplier;

  public static final RocketMqDecorator CONSUMER_DECORATE =
      new RocketMqDecorator(
          Tags.SPAN_KIND_CONSUMER,
          InternalSpanTypes.MESSAGE_CONSUMER,
          SpanNaming.instance().namingSchema().messaging().timeInQueueService(ROCKETMQ));

  public static final RocketMqDecorator PRODUCER_DECORATE =
      new RocketMqDecorator(
          Tags.SPAN_KIND_BROKER,
          InternalSpanTypes.MESSAGE_BROKER,
          SpanNaming.instance().namingSchema().messaging().timeInQueueService(ROCKETMQ));

  protected RocketMqDecorator(
      String spanKind, CharSequence spanType, Supplier<String> serviceNameSupplier) {
    this.spanKind = spanKind;
    this.spanType = spanType;
    this.serviceNameSupplier = serviceNameSupplier;
  }

  @Override
  protected String service() {
    return serviceNameSupplier.get();
  }

  @Override
  protected CharSequence component() {
    return ROCKETMQ_NAME;
  }

  @Override
  protected String spanKind() {
    return spanKind;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"rocketmq", "rocketmq-client"};
  }

  @Override
  protected CharSequence spanType() {
    return ROCKETMQ_NAME;
  }

  private static final String LOCAL_SERVICE_NAME = "rocketmq";

  public AgentScope start(ConsumeMessageContext context) {
    MessageExt ext = context.getMsgList().get(0);

    AgentSpanContext parentContext = extractContextAndGetSpanContext(ext, GETTER);
    UTF8BytesString name = UTF8BytesString.create(ext.getTopic() + " receive");
    AgentSpan span;
    if (ignore) {
      span = startSpan("rocketmq", name);
      if (null != parentContext && null != parentContext.getTraceId()) {
        span.setTag("product_trace_id", parentContext.getTraceId().toString());
        span.setTag("product_span_id", parentContext.getSpanId());
      }
    } else {
      span = startSpan(name, parentContext);
    }

    span.setResourceName(name);

    span.setTag(BROKER_NAME, ext.getBrokerName());
    String tags = ext.getTags();
    if (tags != null) {
      span.setTag(MESSAGING_ROCKETMQ_TAGS, tags);
    }
    span.setTag(TOPIC, ext.getTopic());
    span.setTag(MESSAGING_ROCKETMQ_QUEUE_ID, ext.getQueueId());
    span.setTag(MESSAGING_ROCKETMQ_QUEUE_OFFSET, ext.getQueueOffset());
    span.setTag(MESSAGING_ID, ext.getMsgId());
    SocketAddress storeHost = ext.getStoreHost();
    if (storeHost != null) {
      span.setTag(MESSAGING_ROCKETMQ_BROKER_ADDRESS, getBrokerHost(storeHost));
    }
    afterStart(span);
    AgentScope scope = activateSpan(span);
    if (log.isDebugEnabled()) {
      log.debug("consumer span start topic:{}", ext.getTopic());
    }

    return scope;
  }

  private static String getBrokerHost(SocketAddress storeHost) {
    return storeHost.toString().replace("/", "");
  }

  public void end(ConsumeMessageContext context, AgentScope scope) {
    if (null == scope || null == scope.span()) {
      return;
    }

    String status = context.getStatus();

    AgentSpan span = scope.span();
    span.setTag("status", status);
    beforeFinish(span);

    scope.span().finish();
    scope.close();

    if (log.isDebugEnabled()) {
      log.debug("consumer span end");
    }
  }

  public AgentScope start(SendMessageContext context) {
    String topic = context.getMessage().getTopic();
    UTF8BytesString spanName = UTF8BytesString.create(topic + " send");
    AgentSpan span = startSpan(spanName);
    span.setResourceName(spanName);

    span.setTag(BROKER_HOST, context.getBornHost());
    span.setTag(BROKER_ADDR, context.getBrokerAddr());
    if (context.getMessage() != null) {
      String tags = context.getMessage().getTags();
      if (tags != null) {
        span.setTag(MESSAGING_ROCKETMQ_TAGS, tags);
      }
    }

    Message message = context.getMessage();
    if (null != message) {
      span.setTag(TOPIC, message.getTopic());
    }
    SendResult sendResult = context.getSendResult();
    if (null != sendResult) {
      span.setTag(MESSAGING_ID, sendResult.getMsgId());
    }
    String brokerAddr = context.getBrokerAddr();
    if (brokerAddr != null) {
      span.setTag(MESSAGING_ROCKETMQ_BROKER_ADDRESS, brokerAddr);
    }

    defaultPropagator().inject(span, context, SETTER);
    AgentScope scope = activateSpan(span);
    afterStart(span);
    if (log.isDebugEnabled()) {
      log.debug("consumer span start topic:{}", topic);
    }
    return scope;
  }

  public void end(SendMessageContext context, AgentScope scope) {
    if (scope == null) {
      return;
    }
    Exception exception = context.getException();
    AgentSpan span = scope.span();

    if (span == null) {
      return;
    }
    if (null != exception) {
      onError(span, exception);
    }
    if (context.getSendResult() != null && context.getSendResult().getSendStatus() != null) {
      span.setTag(MESSAGING_ROCKETMQ_SEND_RESULT, context.getSendResult().getSendStatus().name());
    }

    beforeFinish(span);
    scope.span().finish();
    scope.close();

    if (log.isDebugEnabled()) {
      log.debug("consumer span end");
    }
  }
}
