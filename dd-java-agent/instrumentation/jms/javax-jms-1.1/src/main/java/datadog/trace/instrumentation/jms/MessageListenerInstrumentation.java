package datadog.trace.instrumentation.jms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jms.JMSDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.JAVA_JMS;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_CONSUME;
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
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.MessageListener;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class MessageListenerInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "javax.jms.MessageListener";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("javax.jms.MessageListener"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("onMessage"))
            .and(takesArgument(0, named("javax.jms.Message"))),
        MessageListenerInstrumentation.class.getName() + "$ListenerOnMessageAdvice");
  }

  public static class ListenerOnMessageAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(0) Message message) {
      int callDepth = CallDepthThreadLocalMap.incrementCallDepth(MessageListener.class);
      if (callDepth > 0) {
        return null;
      }

      // Extract propagated context from message properties
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

      // Get destination from the message
      Destination destination = null;
      try {
        if (message != null) {
          destination = message.getJMSDestination();
        }
      } catch (Exception e) {
        // ignore
      }
      CONSUMER_DECORATE.onProcess(span, destination);

      // Set DSM checkpoint on consume (message listener)
      String destinationName = extractDestinationName(destination);
      DataStreamsTags dsmTags =
          DataStreamsTags.create("jms", INBOUND, destinationName != null ? destinationName : "");
      AgentTracer.get()
          .getDataStreamsMonitoring()
          .setCheckpoint(span, DataStreamsContext.create(dsmTags, 0, 0));

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter AgentScope scope, @Advice.Thrown Throwable throwable) {
      CallDepthThreadLocalMap.decrementCallDepth(MessageListener.class);
      if (scope == null) {
        return;
      }
      AgentSpan span = scope.span();
      if (throwable != null) {
        CONSUMER_DECORATE.onError(span, throwable);
      }
      CONSUMER_DECORATE.beforeFinish(span);
      span.finish();
      scope.close();
    }
  }
}
