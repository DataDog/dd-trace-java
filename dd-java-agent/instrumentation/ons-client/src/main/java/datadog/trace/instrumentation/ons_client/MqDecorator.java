package datadog.trace.instrumentation.ons_client;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.ons_client.ExtractAdapter.GETTER;

import com.aliyun.openservices.ons.api.Message;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.util.List;

public class MqDecorator extends BaseDecorator {
  public static final CharSequence ROCKETMQ_NAME = UTF8BytesString.create("rocketmq");
  private static final String MESSAGE_ID = "message.id";
  public static final CharSequence MESSAGE_LISTENER = UTF8BytesString.create("messageListener");
  private static final String LOCAL_SERVICE_NAME = Config.get().getServiceName();
  private static final String MESSAGING_ROCKETMQ_BROKER_ADDRESS = "messaging.broker_address";
  private static final String MESSAGE_TAG = "message.tag";
  private static final String MESSAGE_LEN = "message_len";
  public static final MqDecorator DECORATOR= new MqDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[]{"ons-client","rocketmq"};
  }

  @Override
  protected CharSequence spanType() {
    return ROCKETMQ_NAME;
  }

  @Override
  protected CharSequence component() {
    return MESSAGE_LISTENER;
  }

  public AgentScope OnStart(Message message) {
    AgentSpanContext parentContext = extractContextAndGetSpanContext(message,GETTER);
    String topic = message.getTopic();
    UTF8BytesString spanName = UTF8BytesString.create("producer send");
    AgentSpan span;
    if (parentContext == null) {
       span = startSpan(spanName);
    }else {
      span = startSpan(spanName,parentContext);
    }

    span.setTag("topic",topic);
    span.setServiceName("ons-client");
    span.setResourceName("consumer");
    if (message.getTag() != null){
      span.setTag(MESSAGE_TAG,message.getTag());
    }
    span.setTag(MESSAGE_ID,message.getMsgID());
    String key = message.getKey();
    if (key != null){
      span.setTag("key",key);
    }
    String brokerAddr =message.getBornHost();
    if (brokerAddr != null) {
      span.setTag(MESSAGING_ROCKETMQ_BROKER_ADDRESS, brokerAddr);
    }
    afterStart(span);
    return activateSpan(span);
  }

  public AgentScope OnStart(List<Message> messages){
    // 与 OnStart(Message) 不同。这里添加一个tag：message_len
    // 从message[0] 中获取parentContext
    Message message = messages.get(0);
    if (message == null){
      return null;
    }
    AgentScope scope = OnStart(message);
    scope.span().setTag(MESSAGE_LEN,messages.size());
    return scope;
  }

  public void OnEnd( AgentScope scope) {
    beforeFinish(scope);
    scope.close();
    scope.span().finish();
  }

  public void OnEnd( AgentScope scope,String status) {
    scope.span().setTag("message.status",status);
    beforeFinish(scope);
    scope.close();
    scope.span().finish();
  }
}
