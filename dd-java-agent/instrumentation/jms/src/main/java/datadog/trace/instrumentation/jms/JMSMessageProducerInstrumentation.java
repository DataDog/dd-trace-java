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
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Topic;
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
    public static AgentScope onEnter(
        @Advice.Argument(0) final Message message, @Advice.This final MessageProducer producer) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(MessageProducer.class);
      if (callDepth > 0) {
        return null;
      }

      Destination defaultDestination;
      String destinationName = null;
      try {
        defaultDestination = producer.getDestination();
        if (defaultDestination instanceof Queue) {
          destinationName = ((Queue) defaultDestination).getQueueName();
        } else if (defaultDestination instanceof Topic) {
          destinationName = ((Topic) defaultDestination).getTopicName();
        }
      } catch (Exception ignored) {
        defaultDestination = null;
      }

      final AgentSpan span = startSpan(JMS_PRODUCE);
      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onProduce(span, message, defaultDestination);

      if (Config.get().isJMSPropagationEnabled()
          && !Config.get().isJMSPropagationDisabledForDestination(destinationName)) {
        propagate().inject(span, message, SETTER);
      }
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
      CallDepthThreadLocalMap.reset(MessageProducer.class);
    }
  }

  public static class ProducerWithDestinationAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) final Destination destination,
        @Advice.Argument(1) final Message message,
        @Advice.This final MessageProducer producer) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(MessageProducer.class);
      if (callDepth > 0) {
        return null;
      }

      String destinationName = null;
      try {
        if (destination instanceof Queue) {
          destinationName = ((Queue) destination).getQueueName();
        } else if (destination instanceof Topic) {
          destinationName = ((Topic) destination).getTopicName();
        }
      } catch (Exception ignored) {
      }

      final AgentSpan span = startSpan(JMS_PRODUCE);
      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onProduce(span, message, destination);

      if (Config.get().isJMSPropagationEnabled()
          && !Config.get().isJMSPropagationDisabledForDestination(destinationName)) {
        propagate().inject(span, message, SETTER);
      }
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
      CallDepthThreadLocalMap.reset(MessageProducer.class);
    }
  }
}
