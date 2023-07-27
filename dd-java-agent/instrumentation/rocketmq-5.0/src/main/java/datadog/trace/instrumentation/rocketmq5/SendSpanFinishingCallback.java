package datadog.trace.instrumentation.rocketmq5;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.rocketmq.client.java.impl.producer.SendReceiptImpl;
import org.apache.rocketmq.client.java.message.PublishingMessageImpl;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.FutureCallback;

public class SendSpanFinishingCallback implements FutureCallback<SendReceiptImpl> {

  private final AgentSpan scope;

  private final PublishingMessageImpl message;

  public SendSpanFinishingCallback(AgentSpan scope, PublishingMessageImpl message){
    this.message = message;
    this.scope = scope;
  }
  @Override
  public void onSuccess(SendReceiptImpl result) {
    scope.setTag("MessageID",result.getMessageId());
    scope.setSpanType("rocketmq");
    scope.finish();
  }

  @Override
  public void onFailure(Throwable t) {
    scope.setTag("Message_Type",message.getMessageType());
    scope.setErrorMessage(t.getMessage());
    scope.setError(true);
    scope.finish();
  }
}

//  Tried to close datadog.trace.agent.core.scopemanager.ContinuableScope@4373e60c->
//    DDSpan [ t_id=9178204451511121555, s_id=8568375034643761603, p_id=3362837340041904303 ] trace=order-center/dubbo/com.k1k.ordercenter.order.rpc.api.OrderRpcCmdServiceI.placeOrder(PlaceOrderCmd) tags={_sample_rate=1, component=apache-dubbo, dubbo_method=placeOrder, dubbo_short_url=registry://hw-test-nacos.aidyd.com:8848/com.k1k.ordercenter.order.rpc.api.OrderRpcCmdServiceI.placeOrder(PlaceOrderCmd), dubbo_side=provider, dubbo_url=registry://hw-test-nacos.aidyd.com:8848/org.apache.dubbo.registry.RegistryService?application=order&dubbo=2.0.2&namespace=rpc-dev&pid=9&registry=nacos&release=3.0.5&timestamp=1690192157364, dubbo_version=3.0.5, env=test, language=jvm, process_id=9, runtime-id=e9750af1-b4a0-47d7-a5b3-37f28220ac15, thread.id=4817, thread.name=DubboServerHandler-172.20.202.60:20895-thread-200},duration_ns=0
// scope when not on top.
// Current top: datadog.trace.agent.core.scopemanager.ContinuableScope@65c8e2ec->
// DDSpan [ t_id=9178204451511121555, s_id=4239552935852319480, p_id=8568375034643761603 ] trace=order-center/ORDER_AUTO_CLOSE_TOPIC send/order-center tags={bornAddr=10.20.0.10:10101, bornHost=172.20.202.60, brokerName=ORDER_AUTO_CLOSE_TOPIC, env=test, messaging.rocketmq.broker_address=10.20.0.10:10101, messaging.rocketmq.tags=ORDER_AUTO_CLOSE_TAG, thread.id=4817, thread.name=DubboServerHandler-172.20.202.60:20895-thread-200}, duration_ns=0
