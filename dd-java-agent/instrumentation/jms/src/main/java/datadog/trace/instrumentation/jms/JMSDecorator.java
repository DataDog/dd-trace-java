package datadog.trace.instrumentation.jms;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.lang.reflect.Method;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.TemporaryQueue;
import javax.jms.TemporaryTopic;
import javax.jms.Topic;

public final class JMSDecorator extends ClientDecorator {

  public static final CharSequence JMS_CONSUME = UTF8BytesString.createConstant("jms.consume");
  public static final CharSequence JMS_ONMESSAGE = UTF8BytesString.createConstant("jms.onMessage");
  public static final CharSequence JMS_PRODUCE = UTF8BytesString.createConstant("jms.produce");

  private final String spanKind;
  private final String spanType;
  public static final JMSDecorator PRODUCER_DECORATE =
      new JMSDecorator(Tags.SPAN_KIND_PRODUCER, DDSpanTypes.MESSAGE_PRODUCER);

  public static final JMSDecorator CONSUMER_DECORATE =
      new JMSDecorator(Tags.SPAN_KIND_CONSUMER, DDSpanTypes.MESSAGE_CONSUMER);

  public JMSDecorator(String spanKind, String spanType) {
    this.spanKind = spanKind;
    this.spanType = spanType;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jms", "jms-1", "jms-2"};
  }

  @Override
  protected String spanType() {
    return spanType;
  }

  @Override
  protected String service() {
    return "jms";
  }

  @Override
  protected String component() {
    return "jms";
  }

  @Override
  protected String spanKind() {
    return spanKind;
  }

  public void onConsume(final AgentSpan span, final Message message) {
    span.setTag(DDTags.RESOURCE_NAME, "Consumed from " + toResourceName(message, null));
    span.setTag(InstrumentationTags.DD_MEASURED, true);
  }

  public void onReceive(final AgentSpan span, final Method method) {
    span.setTag(DDTags.RESOURCE_NAME, "JMS " + method.getName());
  }

  public void onReceive(final AgentSpan span, final Message message) {
    span.setTag(DDTags.RESOURCE_NAME, "Received from " + toResourceName(message, null));
  }

  public void onProduce(
      final AgentSpan span, final Message message, final Destination destination) {
    span.setTag(DDTags.RESOURCE_NAME, "Produced for " + toResourceName(message, destination));
    span.setTag(InstrumentationTags.DD_MEASURED, true);
  }

  private static final String TIBCO_TMP_PREFIX = "$TMP$";

  public static String toResourceName(final Message message, final Destination destination) {
    Destination jmsDestination = null;
    try {
      jmsDestination = message.getJMSDestination();
    } catch (final Exception e) {
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
    } catch (final Exception e) {
    }
    return "Destination";
  }
}
