package datadog.trace.instrumentation.jms;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.api.datastreams.DataStreamsContext.create;
import static datadog.trace.api.datastreams.DataStreamsContext.fromTags;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;

import datadog.trace.api.Config;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.MessagingClientDecorator;
import java.util.function.Supplier;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.TemporaryQueue;
import javax.jms.TemporaryTopic;
import javax.jms.TextMessage;
import javax.jms.Topic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JmsDecorator extends MessagingClientDecorator {
  private static final Logger log = LoggerFactory.getLogger(JmsDecorator.class);

  public static final CharSequence PRODUCER_OPERATION =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().outboundOperation("jms"));
  public static final CharSequence CONSUMER_OPERATION =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().inboundOperation("jms"));
  public static final CharSequence ONMESSAGE_OPERATION =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().inboundOperation("jms"));

  public static final CharSequence JMS = UTF8BytesString.create("jms");

  public static final boolean JMS_LEGACY_TRACING =
      SpanNaming.instance().namingSchema().allowInferredServices()
          && Config.get().isLegacyTracingEnabled(false, "jms");

  public static final JmsDecorator PRODUCER_DECORATE =
      new JmsDecorator(
          Tags.SPAN_KIND_PRODUCER,
          InternalSpanTypes.MESSAGE_PRODUCER,
          SpanNaming.instance()
              .namingSchema()
              .messaging()
              .outboundService("jms", JMS_LEGACY_TRACING));

  public static final JmsDecorator CONSUMER_DECORATE =
      new JmsDecorator(
          Tags.SPAN_KIND_CONSUMER,
          InternalSpanTypes.MESSAGE_CONSUMER,
          SpanNaming.instance()
              .namingSchema()
              .messaging()
              .inboundService("jms", JMS_LEGACY_TRACING));

  private final String spanKind;
  private final CharSequence spanType;
  private final Supplier<String> serviceNameSupplier;

  public JmsDecorator(
      String spanKind, CharSequence spanType, Supplier<String> serviceNameSupplier) {
    this.spanKind = spanKind;
    this.spanType = spanType;
    this.serviceNameSupplier = serviceNameSupplier;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jms", "jms-1", "jms-2"};
  }

  @Override
  protected String service() {
    return serviceNameSupplier.get();
  }

  @Override
  protected CharSequence component() {
    return JMS;
  }

  @Override
  protected String spanKind() {
    return spanKind;
  }

  @Override
  protected CharSequence spanType() {
    return spanType;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    span.setTag("messaging.system", "jms");
    return super.afterStart(span);
  }

  public void onProduce(final AgentSpan span, final Message message) {
    onProduce(span, message, null);
  }

  public void onProduce(
      final AgentSpan span, final Message message, final Destination destination) {
    span.setTag("messaging.operation", "send");
    try {
      Destination dest = destination != null ? destination : message.getJMSDestination();
      if (dest != null) {
        String destinationName = toDestinationName(dest);
        span.setResourceName("Produced for " + destinationName);
        span.setTag(Tags.MESSAGE_BUS_DESTINATION, destinationName);
        String rawName = extractRawDestinationName(dest);
        if (rawName != null) {
          span.setTag(InstrumentationTags.MESSAGING_DESTINATION_NAME, rawName);
        }
        String kind = destinationKind(dest);
        if (kind != null) {
          span.setTag("messaging.destination.kind", kind);
        }
      } else {
        span.setResourceName("Produced for <unknown>");
      }

      String messageType = getMessageType(message);
      if (messageType != null) {
        span.setTag("jms.message_type", messageType);
      }

      String messageId = message.getJMSMessageID();
      if (messageId != null) {
        span.setTag("message.id", messageId);
      }

      long payloadSize = estimatePayloadSize(message);
      if (payloadSize >= 0) {
        span.setTag("messaging.message.payload_size", payloadSize);
      }
    } catch (final Exception e) {
      log.debug("Error decorating span", e);
    }
  }

  public void onConsume(final AgentSpan span, final Message message) {
    onConsume(span, message, "receive");
  }

  public void onConsume(
      final AgentSpan span, final Message message, final String messagingOperation) {
    span.setTag("messaging.operation", messagingOperation);
    try {
      Destination dest = message.getJMSDestination();
      if (dest != null) {
        String destinationName = toDestinationName(dest);
        span.setResourceName("Consumed from " + destinationName);
        span.setTag(Tags.MESSAGE_BUS_DESTINATION, destinationName);
        String rawName = extractRawDestinationName(dest);
        if (rawName != null) {
          span.setTag(InstrumentationTags.MESSAGING_DESTINATION_NAME, rawName);
        }
        String kind = destinationKind(dest);
        if (kind != null) {
          span.setTag("messaging.destination.kind", kind);
        }
      } else {
        span.setResourceName("Consumed from <unknown>");
      }

      String messageType = getMessageType(message);
      if (messageType != null) {
        span.setTag("jms.message_type", messageType);
      }

      String messageId = message.getJMSMessageID();
      if (messageId != null) {
        span.setTag("message.id", messageId);
      }

      long payloadSize = estimatePayloadSize(message);
      if (payloadSize >= 0) {
        span.setTag("messaging.message.payload_size", payloadSize);
      }
    } catch (final Exception e) {
      log.debug("Error decorating span", e);
    }
  }

  public void injectTraceContext(final AgentSpan span, final Message message) {
    injectTraceContext(span, message, null);
  }

  public void injectTraceContext(
      final AgentSpan span, final Message message, final Destination destination) {
    try {
      String topicName = extractDsmTopicName(message, destination);
      DataStreamsTags tags = DataStreamsTags.create("jms", OUTBOUND, topicName);
      DataStreamsContext dsmContext = fromTags(tags);
      defaultPropagator().inject(span.with(dsmContext), message, MessageInjectAdapter.SETTER);
    } catch (final Exception e) {
      log.debug("Error injecting trace context", e);
    }
  }

  public void setConsumeCheckpoint(final AgentSpan span, final Message message) {
    try {
      String topicName = extractDsmTopicName(message, null);
      DataStreamsTags tags = DataStreamsTags.create("jms", INBOUND, topicName);
      AgentTracer.get().getDataStreamsMonitoring().setCheckpoint(span, create(tags, 0, 0));
    } catch (final Exception e) {
      log.debug("Error setting DSM consume checkpoint", e);
    }
  }

  /** Returns the raw destination name without "Queue "/"Topic " prefix, for DSM tags. */
  private String extractDsmTopicName(Message message, Destination destination) {
    try {
      Destination dest = destination != null ? destination : message.getJMSDestination();
      if (dest instanceof Queue) {
        return ((Queue) dest).getQueueName();
      } else if (dest instanceof Topic) {
        return ((Topic) dest).getTopicName();
      } else if (dest != null) {
        return dest.toString();
      }
    } catch (final JMSException e) {
      log.debug("Error extracting DSM topic name", e);
    }
    return null;
  }

  private static String destinationKind(Destination dest) {
    if (dest instanceof Queue) {
      return "queue";
    } else if (dest instanceof Topic) {
      return "topic";
    }
    return null;
  }

  private String extractRawDestinationName(Destination dest) {
    try {
      if (dest instanceof Queue) {
        return ((Queue) dest).getQueueName();
      } else if (dest instanceof Topic) {
        return ((Topic) dest).getTopicName();
      } else {
        return dest.toString();
      }
    } catch (final JMSException e) {
      log.debug("Error extracting destination name", e);
    }
    return null;
  }

  private String toDestinationName(Destination destination) throws JMSException {
    if (destination instanceof Queue) {
      Queue queue = (Queue) destination;
      if (destination instanceof TemporaryQueue) {
        return "Temporary Queue " + queue.getQueueName();
      } else {
        return "Queue " + queue.getQueueName();
      }
    } else if (destination instanceof Topic) {
      Topic topic = (Topic) destination;
      if (destination instanceof TemporaryTopic) {
        return "Temporary Topic " + topic.getTopicName();
      } else {
        return "Topic " + topic.getTopicName();
      }
    } else {
      return destination.getClass().getSimpleName();
    }
  }

  public static Destination safeGetDestination(MessageProducer producer) {
    try {
      return producer.getDestination();
    } catch (final Exception e) {
      log.debug("Unable to get destination from producer", e);
      return null;
    }
  }

  private long estimatePayloadSize(Message message) {
    try {
      if (message instanceof TextMessage) {
        String text = ((TextMessage) message).getText();
        if (text != null) {
          return text.length();
        }
      }
    } catch (final JMSException e) {
      log.debug("Error estimating payload size", e);
    }
    return -1;
  }

  private String getMessageType(Message message) {
    String className = message.getClass().getName();
    if (className.endsWith("TextMessage")) {
      return "text";
    } else if (className.endsWith("BytesMessage")) {
      return "bytes";
    } else if (className.endsWith("ObjectMessage")) {
      return "object";
    } else if (className.endsWith("MapMessage")) {
      return "map";
    } else if (className.endsWith("StreamMessage")) {
      return "stream";
    } else {
      return null;
    }
  }
}
