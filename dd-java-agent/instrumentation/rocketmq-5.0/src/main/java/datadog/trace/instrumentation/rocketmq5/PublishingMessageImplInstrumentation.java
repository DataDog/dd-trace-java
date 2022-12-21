package datadog.trace.instrumentation.rocketmq5;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.rocketmq.client.java.message.PublishingMessageImpl;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public class PublishingMessageImplInstrumentation  extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy{
  public PublishingMessageImplInstrumentation() {
    super("rocketmq-5.0" );
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return namedOneOf(
        "org.apache.rocketmq.client.java.message.PublishingMessageImpl",
        "org.apache.rocketmq.client.java.message.MessageImpl");
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
    transformation.applyAdvice(
      isConstructor()
        .and(isPublic())
        .and(takesArgument(0, named("org.apache.rocketmq.client.apis.message.Message")))
        .and(
            takesArgument(
                1, named("org.apache.rocketmq.client.java.impl.producer.PublishingSettings")))
        .and(takesArgument(2, boolean.class)),
    PublishingMessageImplInstrumentation.class.getName() + "$ConstructorAdvice");
  }

  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This PublishingMessageImpl message) {
      // VirtualFieldStore.setContextByMessage(message, Context.current());
      //      AgentSpan span = startSpan("rocketmq-5.0");
      //      startSpan("name",span.context());
      //      propagate().inject(span.context(),message,SETTER);
      // 可以存储context
      System.out.println("--------PublishingMessageImplInstrumentation-ConstructorAdvice");
    }
  }
}
