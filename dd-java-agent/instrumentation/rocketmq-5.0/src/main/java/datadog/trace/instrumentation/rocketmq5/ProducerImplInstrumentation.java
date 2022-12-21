package datadog.trace.instrumentation.rocketmq5;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.java.impl.producer.SendReceiptImpl;
import org.apache.rocketmq.client.java.message.PublishingMessageImpl;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.SettableFuture;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.rocketmq5.MessageMapGetter.GETTER;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public class ProducerImplInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy{

  public ProducerImplInstrumentation() {
    super("rocketmq-5.0");
  }
 // public static final CharSequence RocketMQProducerImpl = UTF8BytesString.create("rocketmq-ProducerImpl");

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.rocketmq.client.java.impl.producer.ProducerImpl";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named(hierarchyMarkerType());
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".ReceiveSpanFinishingCallback",
        packageName + ".MessageListenerWrapper",
        packageName + ".MessageMapGetter",
        packageName + ".MessageMapSetter",
        packageName + ".MessageViewGetter",
        packageName + ".MessageViewSetter",
        packageName + ".SendSpanFinishingCallback",
        packageName + ".Timer",
    };
  }
  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    System.out.println("---------ProducerImplInstrumentation-----------");
    transformation.applyAdvice(
        isMethod()
            .and(named("send0"))
            .and(isPrivate())
            .and(takesArguments(6))
            .and(takesArgument(0, named("org.apache.rocketmq.shaded.com.google.common.util.concurrent.SettableFuture")))
            .and(takesArgument(1, String.class))
            .and(takesArgument(2, named("org.apache.rocketmq.client.java.message.MessageType")))
            .and(takesArgument(3, List.class))
            .and(takesArgument(4, List.class))
            .and(takesArgument(5, int.class)),
        ProducerImplInstrumentation.class.getName() + "$SendAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(named("sendAsync"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.apache.rocketmq.client.apis.message.Message"))),
        ProducerImplInstrumentation.class.getName() + "$SendAsyncAdvice");
  }


  // 同步消息
  public static class SendAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) SettableFuture<List<SendReceiptImpl>> future0,
        @Advice.Argument(4) List<PublishingMessageImpl> messages) {

   //   Instrumenter<PublishingMessageImpl, SendReceiptImpl> instrumenter = producerInstrumenter();
      System.out.println("-------------ProducerImplInstrumentation-SendAdvice");
      int count = messages.size();
     // List<SettableFuture<SendReceiptImpl>> futures = convert(future0, count);
      for (int i = 0; i < count; i++) {
        PublishingMessageImpl message = messages.get(i);
        // Try to extract parent context.  尝试获取span 或者 context，如果是消费者主动拉取消息，这里能获取父span
       // Context parentContext = VirtualFieldStore.getContextByMessage(message);
        AgentSpan span;
         AgentSpan.Context parentContext = propagate().extract(message, GETTER);
         if (null == parentContext){
          span  = startSpan("RocketMQProducerImpl");
        }else {
           System.out.println("-----------------get parent context");
           span = startSpan("RocketMQProducerImpl",parentContext);
         }
        AgentScope scope = activateSpan(span);
         scope.span().finish();
     //   SettableFuture<SendReceiptImpl> future = futures.get(i);

//        Futures.addCallback(
//            future0.,
//            new SendSpanFinishingCallback(scope, message),
//            MoreExecutors.directExecutor());
      }
    }
  }

  // 异步消息
  public static class SendAsyncAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Return(readOnly = false) CompletableFuture<SendReceipt> future,
        @Advice.Thrown Throwable throwable) {
      if (throwable == null) {
       // future = CompletableFutureWrapper.wrap(future);
        System.out.println("---------------ProducerImplInstrumentation-SendAdvice");
        System.out.println("t-----------odo  async message");
      }
    }
  }

}
