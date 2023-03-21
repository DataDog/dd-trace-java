package datadog.trace.instrumentation.googlepubsub;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.core.datastreams.TagsProcessor.*;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.googlepubsub.MessageReceiverDecorator.DECORATE;

import com.google.cloud.pubsub.v1.AckReplyConsumerWithResponse;
import com.google.cloud.pubsub.v1.MessageReceiverWithAckResponse;
import com.google.pubsub.v1.PubsubMessage;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import java.util.LinkedHashMap;

public class MessageReceiverWithAckResponseWrapper implements MessageReceiverWithAckResponse {

  private final String subscription;
  private final MessageReceiverWithAckResponse delegate;

  public MessageReceiverWithAckResponseWrapper(
      String subscription, MessageReceiverWithAckResponse delegate) {
    this.subscription = subscription;
    this.delegate = delegate;
  }

  @Override
  public void receiveMessage(PubsubMessage message, AckReplyConsumerWithResponse consumer) {
    final AgentSpan.Context spanContext =
        propagate().extract(message, TextMapExtractAdapter.GETTER);
    AgentSpan span = AgentTracer.startSpan("pubsub", spanContext);
    PathwayContext pathwayContext =
        propagate().extractBinaryPathwayContext(message, TextMapExtractAdapter.GETTER);
    span.mergePathwayContext(pathwayContext);

    LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
    sortedTags.put(DIRECTION_TAG, DIRECTION_IN);
    sortedTags.put(GROUP_TAG, this.subscription);
    sortedTags.put(TYPE_TAG, "google-pubsub");
    AgentTracer.get().setDataStreamCheckpoint(span, sortedTags);

    DECORATE.afterStart(span);

    AgentScope agentScope = activateSpan(span);

    try {
      this.delegate.receiveMessage(message, consumer);
    } finally {
      agentScope.close();
      span.finish();
      closePrevious(true);
    }
  }
}
