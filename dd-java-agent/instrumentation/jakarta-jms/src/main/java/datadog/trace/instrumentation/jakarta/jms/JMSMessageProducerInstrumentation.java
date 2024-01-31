package datadog.trace.instrumentation.jakarta.jms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jakarta.jms.JMSDecorator.JMS_PRODUCE;
import static datadog.trace.instrumentation.jakarta.jms.JMSDecorator.PRODUCER_DECORATE;
import static datadog.trace.instrumentation.jakarta.jms.JMSDecorator.TIME_IN_QUEUE_ENABLED;
import static datadog.trace.instrumentation.jakarta.jms.MessageInjectAdapter.SETTER;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.jms.MessageProducerState;
import jakarta.jms.Destination;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class JMSMessageProducerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public JMSMessageProducerInstrumentation() {
    super("jakarta-jms");
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.jms.MessageProducer";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".JMSDecorator", packageName + ".MessageInjectAdapter"};
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("jakarta.jms.MessageProducer", MessageProducerState.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("send").and(takesArgument(0, named("jakarta.jms.Message"))).and(isPublic()),
        JMSMessageProducerInstrumentation.class.getName() + "$ProducerAdvice");
    transformer.applyAdvice(
        named("send")
            .and(takesArgument(0, named("jakarta.jms.Destination")))
            .and(takesArgument(1, named("jakarta.jms.Message")))
            .and(isPublic()),
        JMSMessageProducerInstrumentation.class.getName() + "$ProducerWithDestinationAdvice");
  }

  public static class ProducerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beforeSend(
        @Advice.Argument(0) final Message message, @Advice.This final MessageProducer producer) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(MessageProducer.class);
      if (callDepth > 0) {
        return null;
      }

      MessageProducerState producerState =
          InstrumentationContext.get(MessageProducer.class, MessageProducerState.class)
              .get(producer);

      CharSequence resourceName;

      if (null != producerState) {
        resourceName = producerState.getResourceName();
      } else {
        try {
          // fall-back when producer wasn't created via standard Session.createProducer API
          Destination destination = producer.getDestination();
          boolean isQueue = PRODUCER_DECORATE.isQueue(destination);
          String destinationName = PRODUCER_DECORATE.getDestinationName(destination);
          resourceName = PRODUCER_DECORATE.toResourceName(destinationName, isQueue);
        } catch (Exception ignored) {
          resourceName = "Unknown Destination";
        }
      }

      final AgentSpan span = startSpan(JMS_PRODUCE);
      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onProduce(span, resourceName);
      if (JMSDecorator.canInject(message)) {
        if (Config.get().isJmsPropagationEnabled()
            && (null == producerState || !producerState.isPropagationDisabled())) {
          propagate().inject(span, message, SETTER);
        }
        if (TIME_IN_QUEUE_ENABLED) {
          if (null != producerState) {
            SETTER.injectTimeInQueue(message, producerState);
          }
        }
      }
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterSend(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      PRODUCER_DECORATE.onError(scope, throwable);
      PRODUCER_DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
      CallDepthThreadLocalMap.reset(MessageProducer.class);
    }
  }

  public static class ProducerWithDestinationAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beforeSend(
        @Advice.Argument(0) final Destination destination,
        @Advice.Argument(1) final Message message,
        @Advice.This final MessageProducer producer) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(MessageProducer.class);
      if (callDepth > 0) {
        return null;
      }

      boolean isQueue = PRODUCER_DECORATE.isQueue(destination);
      String destinationName = PRODUCER_DECORATE.getDestinationName(destination);
      CharSequence resourceName = PRODUCER_DECORATE.toResourceName(destinationName, isQueue);

      final AgentSpan span = startSpan(JMS_PRODUCE);
      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onProduce(span, resourceName);
      if (JMSDecorator.canInject(message)) {
        if (Config.get().isJmsPropagationEnabled()
            && !Config.get().isJmsPropagationDisabledForDestination(destinationName)) {
          propagate().inject(span, message, SETTER);
        }
        if (TIME_IN_QUEUE_ENABLED) {
          MessageProducerState producerState =
              InstrumentationContext.get(MessageProducer.class, MessageProducerState.class)
                  .get(producer);
          if (null != producerState) {
            SETTER.injectTimeInQueue(message, producerState);
          }
        }
      }
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterSend(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      PRODUCER_DECORATE.onError(scope, throwable);
      PRODUCER_DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
      CallDepthThreadLocalMap.reset(MessageProducer.class);
    }
  }
}
