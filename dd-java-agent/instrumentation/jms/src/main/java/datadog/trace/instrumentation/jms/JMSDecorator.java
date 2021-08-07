package datadog.trace.instrumentation.jms;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.RECORD_QUEUE_TIME_MS;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.util.concurrent.TimeUnit;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.TemporaryQueue;
import javax.jms.TemporaryTopic;
import javax.jms.Topic;

public final class JMSDecorator extends ClientDecorator {

  public static final CharSequence JMS = UTF8BytesString.create("jms");
  public static final CharSequence JMS_CONSUME = UTF8BytesString.create("jms.consume");
  public static final CharSequence JMS_PRODUCE = UTF8BytesString.create("jms.produce");

  private final String spanKind;
  private final String spanType;
  private final String serviceName;

  public static final JMSDecorator PRODUCER_DECORATE =
      new JMSDecorator(Tags.SPAN_KIND_PRODUCER, DDSpanTypes.MESSAGE_PRODUCER);

  public static final JMSDecorator CONSUMER_DECORATE =
      new JMSDecorator(Tags.SPAN_KIND_CONSUMER, DDSpanTypes.MESSAGE_CONSUMER);

  public JMSDecorator(String spanKind, String spanType) {
    this.spanKind = spanKind;
    this.spanType = spanType;
    this.serviceName = Config.get().isJmsLegacyTracingEnabled() ? "jms" : null;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jms", "jms-1", "jms-2"};
  }

  @Override
  protected CharSequence spanType() {
    return spanType;
  }

  @Override
  protected String service() {
    return serviceName;
  }

  @Override
  protected CharSequence component() {
    return JMS;
  }

  @Override
  protected String spanKind() {
    return spanKind;
  }

  public void onConsume(final AgentSpan span, final Message message, UTF8BytesString resourceName) {
    if (null != resourceName) {
      span.setResourceName(resourceName);
    }

    try {
      final long produceTime = message.getJMSTimestamp();
      if (produceTime > 0) {
        final long consumeTime = TimeUnit.NANOSECONDS.toMillis(span.getStartTime());
        span.setTag(RECORD_QUEUE_TIME_MS, Math.max(0L, consumeTime - produceTime));
      }
    } catch (Exception ignored) {
    }
    span.setMeasured(true);
  }

  public void onProduce(
      final AgentSpan span, final Message message, final Destination destination) {
    span.setResourceName("Produced for " + toResourceName(message, destination));
    span.setMeasured(true);
  }

  public void onTimeInQueue(
      final AgentSpan span, final String destinationName, UTF8BytesString resourceName) {
    if (null != destinationName) {
      span.setServiceName(destinationName);
    }
    if (null != resourceName) {
      span.setResourceName(resourceName);
    }
  }

  private static final String TIBCO_TMP_PREFIX = "$TMP$";

  public static String toResourceName(final Message message, final Destination destination) {
    Destination jmsDestination = null;
    try {
      jmsDestination = message.getJMSDestination();
    } catch (Exception ignored) {
    }
    if (jmsDestination == null) {
      jmsDestination = destination;
    }
    try {
      if (jmsDestination instanceof Queue) {
        final String queueName = ((Queue) jmsDestination).getQueueName();
        if (jmsDestination instanceof TemporaryQueue || queueName.startsWith(TIBCO_TMP_PREFIX)) {
          return "Temporary Queue";
        } else {
          return "Queue " + queueName;
        }
      }
      if (jmsDestination instanceof Topic) {
        final String topicName = ((Topic) jmsDestination).getTopicName();
        if (jmsDestination instanceof TemporaryTopic || topicName.startsWith(TIBCO_TMP_PREFIX)) {
          return "Temporary Topic";
        } else {
          return "Topic " + topicName;
        }
      }
    } catch (Exception ignored) {
    }
    return "Destination";
  }
}
