package datadog.trace.instrumentation.rocketmq5;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.java.message.MessageBuilderImpl;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.rocketmq5.MessageMapSetter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

@AutoService(Instrumenter.class)
public class MessageImplInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy{
  public static String CLASS_NAME = "";

  public MessageImplInstrumentation() {
    super("rocketmq-5.0");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.rocketmq.client.java.message.MessageBuilderImpl";
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
    };
  }
  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("build"))
            .and(takesArguments(0)),
        MessageImplInstrumentation.class.getName() + "$BuildAdvice"
    );
  }

  public static  class BuildAdvice{
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This MessageBuilderImpl impl){
      AgentSpan span = startSpan("message build send");
      span.setSpanType("rocketmq");
      AgentScope scope = activateSpan(span);
      propagate().inject(span,impl,SETTER);
      return scope;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter AgentScope scope,
        @Advice.Return Message message) {
      scope.span().setServiceName("rocketmq-producer");
      scope.span().setTag("topic",message.getTopic());
      scope.span().setTag("tag",message.getTag());
      scope.span().setTag("messageGroup",message.getMessageGroup());
      scope.span().setTag("keys",message.getKeys());
      scope.close();
      scope.span().finish();
    }
  }
}
