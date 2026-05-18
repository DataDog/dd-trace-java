package datadog.trace.instrumentation.jms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jms.JmsDecorator.PRODUCER_DECORATE;
import static datadog.trace.instrumentation.jms.JmsDecorator.PRODUCER_OPERATION;
import static datadog.trace.instrumentation.jms.JmsDecorator.safeGetDestination;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.MessageProducer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class MessageProducerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public MessageProducerInstrumentation() {
    super("jms", "jms-1", "jms-2");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.jms.MessageProducer";
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
        named("send")
            .and(takesArguments(1))
            .and(takesArgument(0, named("javax.jms.Message")))
            .and(isPublic()),
        getClass().getName() + "$ProducerAdvice");
    transformer.applyAdvice(
        named("send")
            .and(takesArguments(2))
            .and(takesArgument(0, named("javax.jms.Destination")))
            .and(takesArgument(1, named("javax.jms.Message")))
            .and(isPublic()),
        getClass().getName() + "$ProducerWithDestinationAdvice");
    transformer.applyAdvice(
        named("send")
            .and(takesArguments(4))
            .and(takesArgument(0, named("javax.jms.Message")))
            .and(isPublic()),
        getClass().getName() + "$ProducerWithParamsAdvice");
    transformer.applyAdvice(
        named("send")
            .and(takesArguments(5))
            .and(takesArgument(0, named("javax.jms.Destination")))
            .and(takesArgument(1, named("javax.jms.Message")))
            .and(isPublic()),
        getClass().getName() + "$ProducerWithDestinationAndParamsAdvice");
    transformer.applyAdvice(
        named("publish")
            .and(takesArguments(1))
            .and(takesArgument(0, named("javax.jms.Message")))
            .and(isPublic()),
        getClass().getName() + "$ProducerAdvice");
    transformer.applyAdvice(
        named("publish")
            .and(takesArguments(2))
            .and(takesArgument(0, named("javax.jms.Topic")))
            .and(takesArgument(1, named("javax.jms.Message")))
            .and(isPublic()),
        getClass().getName() + "$ProducerWithDestinationAdvice");
    transformer.applyAdvice(
        named("publish")
            .and(takesArguments(4))
            .and(takesArgument(0, named("javax.jms.Message")))
            .and(isPublic()),
        getClass().getName() + "$ProducerWithParamsAdvice");
    transformer.applyAdvice(
        named("publish")
            .and(takesArguments(5))
            .and(takesArgument(0, named("javax.jms.Topic")))
            .and(takesArgument(1, named("javax.jms.Message")))
            .and(isPublic()),
        getClass().getName() + "$ProducerWithDestinationAndParamsAdvice");
    // QueueSender.send(Queue, ...) variants — declared parameter type is Queue, not Destination
    transformer.applyAdvice(
        named("send")
            .and(takesArguments(2))
            .and(takesArgument(0, named("javax.jms.Queue")))
            .and(takesArgument(1, named("javax.jms.Message")))
            .and(isPublic()),
        getClass().getName() + "$ProducerWithDestinationAdvice");
    transformer.applyAdvice(
        named("send")
            .and(takesArguments(5))
            .and(takesArgument(0, named("javax.jms.Queue")))
            .and(takesArgument(1, named("javax.jms.Message")))
            .and(isPublic()),
        getClass().getName() + "$ProducerWithDestinationAndParamsAdvice");
  }

  public static class ProducerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This final MessageProducer producer, @Advice.Argument(0) final Message message) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Message.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(PRODUCER_OPERATION);
      PRODUCER_DECORATE.afterStart(span);
      Destination destination = safeGetDestination(producer);
      PRODUCER_DECORATE.onProduce(span, message, destination);
      PRODUCER_DECORATE.injectTraceContext(span, message, destination);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      PRODUCER_DECORATE.onError(scope, throwable);
      PRODUCER_DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
      CallDepthThreadLocalMap.reset(Message.class);
    }
  }

  public static class ProducerWithDestinationAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) final Destination destination,
        @Advice.Argument(1) final Message message) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Message.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(PRODUCER_OPERATION);
      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onProduce(span, message, destination);
      PRODUCER_DECORATE.injectTraceContext(span, message, destination);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      PRODUCER_DECORATE.onError(scope, throwable);
      PRODUCER_DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
      CallDepthThreadLocalMap.reset(Message.class);
    }
  }

  public static class ProducerWithParamsAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This final MessageProducer producer, @Advice.Argument(0) final Message message) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Message.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(PRODUCER_OPERATION);
      PRODUCER_DECORATE.afterStart(span);
      Destination destination = safeGetDestination(producer);
      PRODUCER_DECORATE.onProduce(span, message, destination);
      PRODUCER_DECORATE.injectTraceContext(span, message, destination);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      PRODUCER_DECORATE.onError(scope, throwable);
      PRODUCER_DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
      CallDepthThreadLocalMap.reset(Message.class);
    }
  }

  public static class ProducerWithDestinationAndParamsAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) final Destination destination,
        @Advice.Argument(1) final Message message) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Message.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(PRODUCER_OPERATION);
      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onProduce(span, message, destination);
      PRODUCER_DECORATE.injectTraceContext(span, message, destination);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      PRODUCER_DECORATE.onError(scope, throwable);
      PRODUCER_DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
      CallDepthThreadLocalMap.reset(Message.class);
    }
  }
}
