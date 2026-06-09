package datadog.trace.instrumentation.jms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jms.JMSDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.JAVA_JMS;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_CONSUME;
import static datadog.trace.instrumentation.jms.JMSDecorator.extractDestinationName;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class MessageConsumerInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "javax.jms.MessageConsumer";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("javax.jms.MessageConsumer"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // receive(), receive(long), receiveNoWait()
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(namedOneOf("receive", "receiveNoWait"))
            .and(returns(named("javax.jms.Message"))),
        MessageConsumerInstrumentation.class.getName() + "$ConsumerReceiveAdvice");

    // setMessageListener(MessageListener) — wrap the listener for tracing
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("setMessageListener"))
            .and(takesArgument(0, named("javax.jms.MessageListener"))),
        MessageConsumerInstrumentation.class.getName() + "$SetMessageListenerAdvice");
  }

  public static class ConsumerReceiveAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
      CallDepthThreadLocalMap.incrementCallDepth(MessageConsumer.class);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This MessageConsumer consumer,
        @Advice.Return Message message,
        @Advice.Thrown Throwable throwable) {
      int callDepth = CallDepthThreadLocalMap.decrementCallDepth(MessageConsumer.class);
      if (callDepth > 0) {
        return;
      }

      if (message == null && throwable == null) {
        // receiveNoWait() returned null — no message, no error, no span
        return;
      }

      // Extract propagated context from message properties if available
      AgentSpanContext.Extracted extractedContext = null;
      if (message != null) {
        extractedContext = extractContextAndGetSpanContext(message, MessageExtractAdapter.GETTER);
      }

      AgentSpan span;
      if (extractedContext != null) {
        span = startSpan(JAVA_JMS.toString(), JMS_CONSUME, extractedContext);
      } else {
        span = startSpan(JAVA_JMS.toString(), JMS_CONSUME);
      }
      CONSUMER_DECORATE.afterStart(span);

      // Get destination from the consumer or message
      Destination destination = null;
      try {
        if (message != null) {
          destination = message.getJMSDestination();
        }
      } catch (Exception e) {
        // ignore
      }
      CONSUMER_DECORATE.onConsume(span, destination);

      // Set DSM checkpoint on consume
      String destinationName = extractDestinationName(destination);
      DataStreamsTags dsmTags =
          DataStreamsTags.create("jms", INBOUND, destinationName != null ? destinationName : "");
      AgentTracer.get()
          .getDataStreamsMonitoring()
          .setCheckpoint(span, DataStreamsContext.create(dsmTags, 0, 0));

      if (throwable != null) {
        CONSUMER_DECORATE.onError(span, throwable);
      }
      CONSUMER_DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  public static class SetMessageListenerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(value = 0, readOnly = false) MessageListener listener) {
      if (listener != null && !(listener instanceof TracingMessageListener)) {
        listener = new TracingMessageListener(listener);
      }
    }
  }
}
