package datadog.trace.instrumentation.jms;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jms.JMSDecorator.JAVA_JMS;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_PRODUCE;
import static datadog.trace.instrumentation.jms.JMSDecorator.PRODUCER_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.extractDestinationName;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.MessageProducer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class MessageProducerInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "javax.jms.MessageProducer";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("javax.jms.MessageProducer"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // send(Message)
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("send"))
            .and(takesArgument(0, named("javax.jms.Message"))),
        MessageProducerInstrumentation.class.getName() + "$ProducerSendAdvice");

    // send(Destination, Message)
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("send"))
            .and(takesArgument(0, named("javax.jms.Destination")))
            .and(takesArgument(1, named("javax.jms.Message"))),
        MessageProducerInstrumentation.class.getName() + "$ProducerSendToDestinationAdvice");
  }

  public static class ProducerSendAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This MessageProducer producer, @Advice.Argument(0) Message message) {
      int callDepth = CallDepthThreadLocalMap.incrementCallDepth(MessageProducer.class);
      if (callDepth > 0) {
        return null;
      }

      Destination destination = null;
      try {
        destination = producer.getDestination();
      } catch (Exception e) {
        // ignore
      }

      AgentSpan span = startSpan(JAVA_JMS.toString(), JMS_PRODUCE);
      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onProduce(span, destination);

      // Inject trace context and DSM pathway context into message properties
      String destinationName = extractDestinationName(destination);
      DataStreamsTags dsmTags =
          DataStreamsTags.create("jms", OUTBOUND, destinationName != null ? destinationName : "");
      DataStreamsContext dsmContext = DataStreamsContext.fromTags(dsmTags);
      defaultPropagator().inject(span.with(dsmContext), message, MessageInjectAdapter.SETTER);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter AgentScope scope, @Advice.Thrown Throwable throwable) {
      CallDepthThreadLocalMap.reset(MessageProducer.class);
      if (scope == null) {
        return;
      }
      AgentSpan span = scope.span();
      PRODUCER_DECORATE.onError(span, throwable);
      PRODUCER_DECORATE.beforeFinish(span);
      span.finish();
      scope.close();
    }
  }

  public static class ProducerSendToDestinationAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) Destination destination, @Advice.Argument(1) Message message) {
      int callDepth = CallDepthThreadLocalMap.incrementCallDepth(MessageProducer.class);
      if (callDepth > 0) {
        return null;
      }

      AgentSpan span = startSpan(JAVA_JMS.toString(), JMS_PRODUCE);
      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onProduce(span, destination);

      // Inject trace context and DSM pathway context into message properties
      String destinationName = extractDestinationName(destination);
      DataStreamsTags dsmTags =
          DataStreamsTags.create("jms", OUTBOUND, destinationName != null ? destinationName : "");
      DataStreamsContext dsmContext = DataStreamsContext.fromTags(dsmTags);
      defaultPropagator().inject(span.with(dsmContext), message, MessageInjectAdapter.SETTER);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter AgentScope scope, @Advice.Thrown Throwable throwable) {
      CallDepthThreadLocalMap.reset(MessageProducer.class);
      if (scope == null) {
        return;
      }
      AgentSpan span = scope.span();
      PRODUCER_DECORATE.onError(span, throwable);
      PRODUCER_DECORATE.beforeFinish(span);
      span.finish();
      scope.close();
    }
  }
}
