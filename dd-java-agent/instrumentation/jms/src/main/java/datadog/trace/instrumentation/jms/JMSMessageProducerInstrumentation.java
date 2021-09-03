package datadog.trace.instrumentation.jms;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_PRODUCE;
import static datadog.trace.instrumentation.jms.JMSDecorator.PRODUCER_DECORATE;
import static datadog.trace.instrumentation.jms.MessageInjectAdapter.SETTER;
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
import java.util.Map;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class JMSMessageProducerInstrumentation extends Instrumenter.Tracing {

  public JMSMessageProducerInstrumentation() {
    super("jms", "jms-1", "jms-2");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("javax.jms.MessageProducer");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("javax.jms.MessageProducer"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JMSDecorator",
      packageName + ".MessageExtractAdapter",
      packageName + ".MessageExtractAdapter$1",
      packageName + ".MessageInjectAdapter"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("javax.jms.MessageProducer", MessageProducerState.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("send").and(takesArgument(0, named("javax.jms.Message"))).and(isPublic()),
        JMSMessageProducerInstrumentation.class.getName() + "$ProducerAdvice");
    transformation.applyAdvice(
        named("send")
            .and(takesArgument(0, named("javax.jms.Destination")))
            .and(takesArgument(1, named("javax.jms.Message")))
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
      if (null == producerState) {
        return null;
      }

      final AgentSpan span = startSpan(JMS_PRODUCE);
      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onProduce(span, producerState.getResourceName());
      if (Config.get().isJMSPropagationEnabled() && !producerState.isPropagationDisabled()) {
        propagate().inject(span, message, SETTER);
      }
      if (!Config.get().isJmsLegacyTracingEnabled()) {
        SETTER.injectTimeInQueue(message, producerState.getSessionState());
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

      MessageProducerState producerState =
          InstrumentationContext.get(MessageProducer.class, MessageProducerState.class)
              .get(producer);
      if (null == producerState) {
        return null;
      }

      String destinationName = PRODUCER_DECORATE.getDestinationName(destination);
      CharSequence resourceName =
          PRODUCER_DECORATE.toResourceName(destinationName, destination instanceof Queue);

      final AgentSpan span = startSpan(JMS_PRODUCE);
      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onProduce(span, resourceName);
      if (Config.get().isJMSPropagationEnabled()
          && !Config.get().isJMSPropagationDisabledForDestination(destinationName)) {
        propagate().inject(span, message, SETTER);
      }
      if (!Config.get().isJmsLegacyTracingEnabled()) {
        SETTER.injectTimeInQueue(message, producerState.getSessionState());
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
