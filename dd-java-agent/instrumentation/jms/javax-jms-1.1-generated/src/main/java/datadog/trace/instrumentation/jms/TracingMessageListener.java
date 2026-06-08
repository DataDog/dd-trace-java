package datadog.trace.instrumentation.jms;

import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jms.JMSDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.JAVA_JMS;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_CONSUME;
import static datadog.trace.instrumentation.jms.JMSDecorator.extractDestinationName;

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

public class TracingMessageListener implements MessageListener {
  private final MessageListener delegate;

  public TracingMessageListener(MessageListener delegate) {
    this.delegate = delegate;
  }

  @Override
  public void onMessage(Message message) {
    // Coordinate with MessageListenerInstrumentation advice to prevent double spans.
    // Both this wrapper and the bytecode advice use MessageListener.class as the depth key.
    int callDepth = CallDepthThreadLocalMap.incrementCallDepth(MessageListener.class);
    if (callDepth > 0) {
      // Already being traced by MessageListenerInstrumentation advice
      try {
        delegate.onMessage(message);
      } finally {
        CallDepthThreadLocalMap.decrementCallDepth(MessageListener.class);
      }
      return;
    }

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

    AgentScope scope = activateSpan(span);
    try {
      delegate.onMessage(message);
    } catch (Throwable throwable) {
      CONSUMER_DECORATE.onError(span, throwable);
      throw throwable;
    } finally {
      CallDepthThreadLocalMap.decrementCallDepth(MessageListener.class);
      CONSUMER_DECORATE.beforeFinish(span);
      span.finish();
      scope.close();
    }
  }
}
