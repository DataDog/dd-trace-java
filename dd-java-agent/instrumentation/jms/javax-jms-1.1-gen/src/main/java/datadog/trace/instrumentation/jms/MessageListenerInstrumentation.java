package datadog.trace.instrumentation.jms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jms.JmsDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.jms.JmsDecorator.ONMESSAGE_OPERATION;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import javax.jms.Message;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class MessageListenerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public MessageListenerInstrumentation() {
    super("jms", "jms-1", "jms-2");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.jms.MessageListener";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JmsDecorator",
      packageName + ".MessageExtractAdapter",
      packageName + ".MessageInjectAdapter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("onMessage")
            .and(takesArguments(1))
            .and(takesArgument(0, named("javax.jms.Message")))
            .and(isPublic()),
        getClass().getName() + "$OnMessageAdvice");
  }

  public static class OnMessageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(0) final Message message) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Message.class);
      if (callDepth > 0) {
        return null;
      }

      MessageExtractAdapter extractor = new MessageExtractAdapter(message);
      AgentSpanContext.Extracted extractedContext =
          extractContextAndGetSpanContext(message, extractor);

      final AgentSpan span;
      if (extractedContext != null) {
        span = startSpan(ONMESSAGE_OPERATION, extractedContext);
      } else {
        span = startSpan(ONMESSAGE_OPERATION);
      }
      CONSUMER_DECORATE.afterStart(span);
      CONSUMER_DECORATE.onConsume(span, message, "process");
      CONSUMER_DECORATE.setConsumeCheckpoint(span, message);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      CONSUMER_DECORATE.onError(scope, throwable);
      CONSUMER_DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
      CallDepthThreadLocalMap.reset(Message.class);
    }
  }
}
