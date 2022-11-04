package datadog.trace.instrumentation.pubsub;

import com.google.auto.service.AutoService;
import com.google.cloud.pubsub.v1.MessageReceiver;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

import java.util.Collections;
import java.util.Map;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

@AutoService(Instrumenter.class)
public final class MessageReceiverInstrumentation
    extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public MessageReceiverInstrumentation() {
    super("pubsub");
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".MessageReceiverDecorator",
        packageName + ".TextMapExtractAdapter",
        packageName + ".MessageReceiverWrapper",
    };
  }

  @Override
  public String instrumentedType() {

    return "org.springframework.cloud.gcp.pubsub.support.DefaultSubscriberFactory";
    //return "com.google.cloud.pubsub.v1.Subscriber";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {

    System.out.println("========================> advise --> " + getClass().getName() + "$Wrap");

    transformation.applyAdvice(
        isMethod().and(named("createSubscriber")), getClass().getName() + "$Wrap");

    //transformation.applyAdvice(
    //    isMethod()
    //        .and(named("newBuilder"))
    //        .and(isStatic())
    //        .and(takesArgument(0, String.class))
    //        .and(takesArgument(1, named("com.google.cloud.pubsub.v1.MessageReceiver")))
    //    ,
    //    getClass().getName() + "$Wrap");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(instrumentedType(), AgentSpan.class.getName());
  }

  public static final class Wrap {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(@Advice.Argument(value = 1, readOnly = false) MessageReceiver receiver) {
      System.out.println("--------------------> INSTRUMEEEEEEEEEEEEEEEEEEEEEEEEEEENT");
      receiver = new MessageReceiverWrapper(receiver);
    }
  }

}
