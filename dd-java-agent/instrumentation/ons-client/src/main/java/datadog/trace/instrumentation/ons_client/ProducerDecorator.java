package datadog.trace.instrumentation.ons_client;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.ons_client.InjectAdapter.SETTER;

import com.aliyun.openservices.ons.api.Message;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class ProducerDecorator extends BaseDecorator {
  public static final CharSequence ROCKETMQ_NAME = UTF8BytesString.create("rocketmq");
  private static final String MESSAGE_TAG = "message.tag";

  private static final String LOCAL_SERVICE_NAME = Config.get().getServiceName();
  public static final CharSequence PRODUCER = UTF8BytesString.create("producer send");
  private static final String MESSAGING_ROCKETMQ_BROKER_ADDRESS = "messaging.broker_address";

  public static final ProducerDecorator PRODUCER_DECORATOR = new ProducerDecorator();

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
    return PRODUCER;
  }

  public AgentScope OnStart(Message message){
    String topic = message.getTopic();
    AgentSpan span = startSpan(topic+" send");
    span.setResourceName(topic + " send");
    span.setServiceName("ons-client");
    if (message.getTag() != null){
      span.setTag(MESSAGE_TAG,message.getTag());
    }
    String brokerAddr =message.getBornHost();
    if (brokerAddr != null) {
      span.setTag(MESSAGING_ROCKETMQ_BROKER_ADDRESS, brokerAddr);
    }
    span.setTag("topic",topic);
    //span.setTag(MESSAGE_ID,message.getMsgID());
    defaultPropagator().inject(span,message,SETTER); // 传递链路信息
    afterStart(span);
    return activateSpan(span);
  }

  public void OnEnd(AgentScope scope, Throwable throwable){
    if (scope == null) {
      return;
    }
    onError(scope.span(),throwable);
    beforeFinish(scope.span());
    scope.close();
    scope.span().finish();
  }
}
