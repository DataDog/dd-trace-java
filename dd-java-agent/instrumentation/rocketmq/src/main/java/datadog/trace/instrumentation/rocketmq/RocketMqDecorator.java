package datadog.trace.instrumentation.rocketmq;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import org.apache.rocketmq.client.hook.ConsumeMessageContext;
import org.apache.rocketmq.client.hook.SendMessageContext;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;

import java.net.SocketAddress;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.rocketmq.TextMapExtractAdapter.GETTER;
import static datadog.trace.instrumentation.rocketmq.TextMapInjectAdapter.SETTER;

public class RocketMqDecorator extends BaseDecorator {
  public static final CharSequence ROCKETMQ_NAME = UTF8BytesString.create("rocketmq");
  private static final String BROKER_HOST = "bornHost";
  private static final String BROKER_ADDR = "bornAddr";
  private static final String BROKER_NAME = "brokerName";
  private static final String TOPIC = "brokerName";
  private static final String MESSAGING_ROCKETMQ_TAGS = "messaging.rocketmq.tags";
  private static final String MESSAGING_ROCKETMQ_BROKER_ADDRESS = "messaging.rocketmq.broker_address";
  private static final String MESSAGING_ROCKETMQ_SEND_RESULT = "messaging.rocketmq.send_result";
  private static final String MESSAGING_ROCKETMQ_QUEUE_ID = "messaging.rocketmq.queue_id";
  private static final String MESSAGING_ID = "messaging.id";
  private static final String MESSAGING_ROCKETMQ_QUEUE_OFFSET = "messaging.rocketmq.queue_offset";

  RocketMqDecorator() {
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[]{"rocketmq", "rocketmq-client"};
  }

  @Override
  protected CharSequence spanType() {
    return ROCKETMQ_NAME;
  }

  @Override
  protected CharSequence component() {
    return null;
  }

  private static final String LOCAL_SERVICE_NAME =
      Config.get().getServiceName();


  public AgentScope start(ConsumeMessageContext context) {
    MessageExt ext = context.getMsgList().get(0);
    AgentSpan.Context parentContext = propagate().extract(ext, GETTER);
    UTF8BytesString name = UTF8BytesString.create(ext.getTopic() + " send");
    final AgentSpan span = startSpan(name, parentContext);
    span.setResourceName(LOCAL_SERVICE_NAME);

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

    return scope;
  }

  private static String getBrokerHost(SocketAddress storeHost) {
    return storeHost.toString().replace("/", "");
  }

  public void end(ConsumeMessageContext context, AgentScope scope) {
    String status = context.getStatus();
    scope.span().setTag("status", status);
    beforeFinish(scope);
    scope.close();
    scope.span().finish();
  }

  public AgentScope start(SendMessageContext context) {
    String topic = context.getMessage().getTopic();
    UTF8BytesString spanName = UTF8BytesString.create(topic + " send");
    final AgentSpan span = startSpan(spanName);
    span.setResourceName(LOCAL_SERVICE_NAME);
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

    afterStart(span);
    propagate().inject(span, context, SETTER);
    AgentScope scope = activateSpan(span);
    return scope;
  }

  public void end(SendMessageContext context, AgentScope scope) {
    Exception exception = context.getException();
    if (null != exception) {
      onError(scope, exception);
    }
    scope.span().setTag(MESSAGING_ROCKETMQ_SEND_RESULT, context.getSendResult().getSendStatus().name());
    beforeFinish(scope.span());
    scope.close();
    scope.span().finish();
  }
}

