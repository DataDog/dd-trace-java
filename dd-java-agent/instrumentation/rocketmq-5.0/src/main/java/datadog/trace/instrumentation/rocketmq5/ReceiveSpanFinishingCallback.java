package datadog.trace.instrumentation.rocketmq5;

import apache.rocketmq.v2.ReceiveMessageRequest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.rocketmq.client.java.impl.consumer.ReceiveMessageResult;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.FutureCallback;

import java.util.List;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.rocketmq5.MessageViewSetter.setterView;

public class ReceiveSpanFinishingCallback implements FutureCallback<ReceiveMessageResult> {

  private final ReceiveMessageRequest request;
  private final Timer timer;

  public ReceiveSpanFinishingCallback(ReceiveMessageRequest request, Timer timer) {
    this.request = request;
    this.timer = timer;
  }

  @Override
  public void onSuccess(ReceiveMessageResult receiveMessageResult) {
    List<MessageViewImpl> messageViews = receiveMessageResult.getMessageViewImpls();
    // Don't create spans when no messages were received.
    if (messageViews.isEmpty()) {
      return;
    }
    String consumerGroup = request.getGroup().getName();

//    ContextStore<MessageViewImpl, String> groupStore = InstrumentationContext.get(MessageViewImpl.class, String.class);
//
//    for (MessageViewImpl messageView : messageViews) {
//      //VirtualFieldStore.setConsumerGroupByMessage(messageView, consumerGroup);
//      // todo  使用 store 存储
//      groupStore.put(messageView,consumerGroup);
//    }
    // 父span start
    AgentSpan span = startSpan("receive_message");// todo start time
    AgentScope scope = activateSpan(span);
    span.setResourceName("rocketmq5");
    span.setTag("groupID",consumerGroup);
    //
    for (MessageViewImpl messageView : messageViews) {
      propagate().inject(span.context(),messageView,setterView);
    }
    scope.close();
    scope.span().finish();
    //
    //
    /*
    Instrumenter<ReceiveMessageRequest, List<MessageView>> receiveInstrumenter =
        RocketMqSingletons.consumerReceiveInstrumenter();
    Context parentContext = Context.current();
    if (receiveInstrumenter.shouldStart(parentContext, request)) {
      Context context =
          InstrumenterUtil.startAndEnd(
              receiveInstrumenter,
              parentContext,
              request,
              null,
              null,
              timer.startTime(),
              timer.now());

    }*/

  }

  @Override
  public void onFailure(Throwable throwable) {
  /*  Instrumenter<ReceiveMessageRequest, List<MessageView>> receiveInstrumenter =
        RocketMqSingletons.consumerReceiveInstrumenter();
    Context parentContext = Context.current();
    if (receiveInstrumenter.shouldStart(parentContext, request)) {
      InstrumenterUtil.startAndEnd(
          receiveInstrumenter,
          parentContext,
          request,
          null,
          throwable,
          timer.startTime(),
          timer.now());
    }*/
  }
}
