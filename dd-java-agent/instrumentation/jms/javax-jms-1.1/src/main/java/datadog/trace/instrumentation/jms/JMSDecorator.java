package datadog.trace.instrumentation.jms;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.MESSAGING_DESTINATION_NAME;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.MessagingClientDecorator;
import java.util.function.Supplier;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.TemporaryQueue;
import javax.jms.TemporaryTopic;
import javax.jms.Topic;

public class JMSDecorator extends MessagingClientDecorator {
  private static final String JMS = "jms";
  public static final CharSequence JAVA_JMS = UTF8BytesString.create("jms");

  public static final CharSequence JMS_PRODUCE =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().outboundOperation(JMS));

  public static final CharSequence JMS_CONSUME =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().inboundOperation(JMS));

  public static final CharSequence JMS_ACKNOWLEDGE = UTF8BytesString.create("jms.acknowledge");

  private final String spanKind;
  private final CharSequence spanType;
  private final Supplier<String> serviceNameSupplier;

  public static final JMSDecorator PRODUCER_DECORATE =
      new JMSDecorator(
          Tags.SPAN_KIND_PRODUCER,
          InternalSpanTypes.MESSAGE_PRODUCER,
          SpanNaming.instance().namingSchema().messaging().outboundService(JMS, false));

  public static final JMSDecorator CONSUMER_DECORATE =
      new JMSDecorator(
          Tags.SPAN_KIND_CONSUMER,
          InternalSpanTypes.MESSAGE_CONSUMER,
          SpanNaming.instance().namingSchema().messaging().inboundService(JMS, false));

  public static final JMSDecorator ACKNOWLEDGE_DECORATE =
      new JMSDecorator(
          Tags.SPAN_KIND_INTERNAL,
          InternalSpanTypes.MESSAGE_CLIENT,
          SpanNaming.instance().namingSchema().messaging().inboundService(JMS, false));

  protected JMSDecorator(
      String spanKind, CharSequence spanType, Supplier<String> serviceNameSupplier) {
    this.spanKind = spanKind;
    this.spanType = spanType;
    this.serviceNameSupplier = serviceNameSupplier;
  }

  @Override
  protected CharSequence spanType() {
    return spanType;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jms"};
  }

  @Override
  protected String service() {
    return serviceNameSupplier.get();
  }

  @Override
  protected CharSequence component() {
    return JAVA_JMS;
  }

  @Override
  protected String spanKind() {
    return spanKind;
  }

  public void onProduce(AgentSpan span, Destination destination) {
    String destinationName = extractDestinationName(destination);
    if (destinationName != null) {
      String kindLabel = destinationKindLabel(destination);
      span.setResourceName("Produce " + kindLabel + " " + destinationName);
      span.setTag(MESSAGING_DESTINATION_NAME, destinationName);
    }
    String destinationKind = extractDestinationKind(destination);
    if (destinationKind != null) {
      span.setTag("messaging.destination.kind", destinationKind);
    }
    span.setTag("messaging.system", "jms");
    span.setTag("messaging.operation", "send");
  }

  public void onConsume(AgentSpan span, Destination destination) {
    String destinationName = extractDestinationName(destination);
    if (destinationName != null) {
      String kindLabel = destinationKindLabel(destination);
      span.setResourceName("Consume " + kindLabel + " " + destinationName);
      span.setTag(MESSAGING_DESTINATION_NAME, destinationName);
    }
    String destinationKind = extractDestinationKind(destination);
    if (destinationKind != null) {
      span.setTag("messaging.destination.kind", destinationKind);
    }
    span.setTag("messaging.system", "jms");
    span.setTag("messaging.operation", "receive");
  }

  public void onProcess(AgentSpan span, Destination destination) {
    String destinationName = extractDestinationName(destination);
    if (destinationName != null) {
      String kindLabel = destinationKindLabel(destination);
      span.setResourceName("Consume " + kindLabel + " " + destinationName);
      span.setTag(MESSAGING_DESTINATION_NAME, destinationName);
    }
    String destinationKind = extractDestinationKind(destination);
    if (destinationKind != null) {
      span.setTag("messaging.destination.kind", destinationKind);
    }
    span.setTag("messaging.system", "jms");
    span.setTag("messaging.operation", "process");
  }

  public void onAcknowledge(AgentSpan span, Destination destination) {
    String destinationName = extractDestinationName(destination);
    if (destinationName != null) {
      span.setTag(MESSAGING_DESTINATION_NAME, destinationName);
    }
    span.setTag("messaging.system", "jms");
    span.setTag("messaging.operation", "acknowledge");
  }

  public static String extractDestinationName(Destination destination) {
    if (destination == null) {
      return null;
    }
    try {
      if (destination instanceof TemporaryQueue) {
        return "Temporary Queue";
      } else if (destination instanceof TemporaryTopic) {
        return "Temporary Topic";
      } else if (destination instanceof Queue) {
        return ((Queue) destination).getQueueName();
      } else if (destination instanceof Topic) {
        return ((Topic) destination).getTopicName();
      }
    } catch (JMSException e) {
      // ignore
    }
    return "unknown";
  }

  /** Returns "Queue" or "Topic" label for resource names based on the JMS destination type. */
  private static String destinationKindLabel(Destination destination) {
    if (destination instanceof Queue) {
      return "Queue";
    } else if (destination instanceof Topic) {
      return "Topic";
    }
    return "Topic";
  }

  /** Returns "queue" or "topic" based on the JMS destination type, or null if unknown. */
  public static String extractDestinationKind(Destination destination) {
    if (destination == null) {
      return null;
    }
    if (destination instanceof Queue) {
      return "queue";
    } else if (destination instanceof Topic) {
      return "topic";
    }
    return null;
  }
}
