package datadog.trace.instrumentation.rocketmq5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import apache.rocketmq.v2.ReceiveMessageRequest;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.rocketmq.client.java.impl.consumer.ReceiveMessageResult;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.Futures;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ListenableFuture;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.MoreExecutors;

@AutoService(InstrumenterModule.class)
public  class ConsumerImplInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice{

  public static final String CLASS_NAME = "org.apache.rocketmq.client.java.impl.consumer.ConsumerImpl";

  public ConsumerImplInstrumentation() {
    super("rocketmq5", "rocketmq-client-java");
  }

  @Override
  public String hierarchyMarkerType() {
    return CLASS_NAME;
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
        packageName + ".MessageMapSetter",
        packageName + ".MessageViewGetter",
        packageName + ".SendSpanFinishingCallback",
        packageName + ".Timer",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(
        isMethod()
        .and(named("receiveMessage"))
        .and(takesArguments(3))
        .and(takesArgument(0, named("apache.rocketmq.v2.ReceiveMessageRequest")))
        .and(takesArgument(1, named("org.apache.rocketmq.client.java.route.MessageQueueImpl")))
        .and(takesArgument(2, named("java.time.Duration"))),
        ConsumerImplInstrumentation.class.getName() + "$ReceiveMessageAdvice");
  }


  public static class ReceiveMessageAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Timer onStart() {
      // todo span start
      return Timer.start();
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) ReceiveMessageRequest request,
        @Advice.Enter Timer timer,
        @Advice.Return ListenableFuture<ReceiveMessageResult> future) {
      ReceiveSpanFinishingCallback spanFinishingCallback =
          new ReceiveSpanFinishingCallback(request, timer);
      Futures.addCallback(future, spanFinishingCallback, MoreExecutors.directExecutor());
    }
  }
}

