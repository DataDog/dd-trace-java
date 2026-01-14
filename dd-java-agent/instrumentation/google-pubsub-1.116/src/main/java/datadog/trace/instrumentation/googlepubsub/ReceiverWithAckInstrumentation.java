package datadog.trace.instrumentation.googlepubsub;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.cloud.pubsub.v1.MessageReceiverWithAckResponse;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

public final class ReceiverWithAckInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.google.cloud.pubsub.v1.Subscriber";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("newBuilder"))
            .and(takesArgument(0, String.class))
            .and(takesArgument(1, nameEndsWith("MessageReceiverWithAckResponse"))),
        getClass().getName() + "$Wrap");
  }

  public static final class Wrap {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(
        @Advice.Argument(value = 0) final String subscription,
        @Advice.Argument(value = 1, readOnly = false) MessageReceiverWithAckResponse receiver) {
      receiver = new MessageReceiverWithAckResponseWrapper(subscription, receiver);
    }
  }
}
