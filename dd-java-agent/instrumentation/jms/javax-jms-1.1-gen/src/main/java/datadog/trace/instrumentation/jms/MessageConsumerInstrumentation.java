package datadog.trace.instrumentation.jms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jms.JmsDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.jms.JmsDecorator.CONSUMER_OPERATION;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import javax.jms.Message;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class MessageConsumerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public MessageConsumerInstrumentation() {
    super("jms", "jms-1", "jms-2");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.jms.MessageConsumer";
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
        namedOneOf("receive", "receiveNoWait").and(takesArguments(0)).and(isPublic()),
        getClass().getName() + "$ConsumerAdvice");
    transformer.applyAdvice(
        named("receive").and(takesArguments(1)).and(isPublic()),
        getClass().getName() + "$ConsumerAdvice");
  }

  public static class ConsumerAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Return final Message message, @Advice.Thrown final Throwable throwable) {
      if (message == null) {
        return;
      }

      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Message.class);
      if (callDepth > 0) {
        CallDepthThreadLocalMap.decrementCallDepth(Message.class);
        return;
      }

      try {
        MessageExtractAdapter extractor = new MessageExtractAdapter(message);
        AgentSpanContext.Extracted extractedContext =
            extractContextAndGetSpanContext(message, extractor);

        final AgentSpan span;
        if (extractedContext != null) {
          span = startSpan(CONSUMER_OPERATION, extractedContext);
        } else {
          span = startSpan(CONSUMER_OPERATION);
        }
        CONSUMER_DECORATE.afterStart(span);
        CONSUMER_DECORATE.onConsume(span, message);
        CONSUMER_DECORATE.setConsumeCheckpoint(span, message);

        if (throwable != null) {
          CONSUMER_DECORATE.onError(span, throwable);
        }

        CONSUMER_DECORATE.beforeFinish(span);
        span.finish();
      } finally {
        CallDepthThreadLocalMap.reset(Message.class);
      }
    }
  }
}
