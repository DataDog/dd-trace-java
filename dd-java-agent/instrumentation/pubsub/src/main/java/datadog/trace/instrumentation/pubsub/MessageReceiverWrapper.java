package datadog.trace.instrumentation.pubsub;

import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.pubsub.v1.PubsubMessage;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;

import java.util.LinkedHashMap;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.pubsub.MessageReceiverDecorator.DECORATE;

public final class MessageReceiverWrapper implements MessageReceiver {

  private final MessageReceiver delegate;

  public MessageReceiverWrapper(MessageReceiver delegate) {
    this.delegate = delegate;
  }

  @Override
  public void receiveMessage(PubsubMessage message, AckReplyConsumer consumer) {

    System.out.println("====================> WRAPPER before: " + message.getAttributesMap());

    final AgentSpan.Context spanContext = propagate().extract(message, TextMapExtractAdapter.GETTER);
    AgentSpan span = AgentTracer.startSpan("pubsub", spanContext);
    PathwayContext pathwayContext =
        propagate().extractBinaryPathwayContext(message, TextMapExtractAdapter.GETTER);
    span.mergePathwayContext(pathwayContext);

    LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
    sortedTags.put(TYPE_TAG, "pubsub");
    AgentTracer.get().setDataStreamCheckpoint(span, sortedTags);

    DECORATE.afterStart(span);

    activateNext(span);

    try {
      this.delegate.receiveMessage(message, consumer);
    } finally {
      System.out.println("====================> after");
      closePrevious(true);
    }
  }
}
