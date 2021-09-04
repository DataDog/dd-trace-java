package datadog.trace.instrumentation.jms;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.RECORD_QUEUE_TIME_MS;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.Function;
import datadog.trace.api.Functions.Join;
import datadog.trace.api.Functions.PrefixJoin;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
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
  public static final CharSequence JMS_DELIVER = UTF8BytesString.create("jms.deliver");

  private static final Join QUEUE_JOINER = PrefixJoin.of("Queue ");
  private static final Join TOPIC_JOINER = PrefixJoin.of("Topic ");

  private final DDCache<CharSequence, CharSequence> resourceNameCache =
      DDCaches.newFixedSizeCache(32);

  private final String resourcePrefix;

  private final UTF8BytesString queueTempResourceName;
  private final UTF8BytesString topicTempResourceName;

  private final Function<CharSequence, CharSequence> queueResourceJoiner;
  private final Function<CharSequence, CharSequence> topicResourceJoiner;

  private final String spanKind;
  private final CharSequence spanType;
  private final String serviceName;

  public static final JMSDecorator PRODUCER_DECORATE =
      new JMSDecorator(
          "Produced for ",
          Tags.SPAN_KIND_PRODUCER,
          InternalSpanTypes.MESSAGE_PRODUCER,
          Config.get().isJmsLegacyTracingEnabled() ? "jms" : Config.get().getServiceName());

  public static final JMSDecorator CONSUMER_DECORATE =
      new JMSDecorator(
          "Consumed from ",
          Tags.SPAN_KIND_CONSUMER,
          InternalSpanTypes.MESSAGE_CONSUMER,
          Config.get().isJmsLegacyTracingEnabled() ? "jms" : Config.get().getServiceName());

  public static final JMSDecorator BROKER_DECORATE =
      new JMSDecorator("", Tags.SPAN_KIND_BROKER, DDSpanTypes.MESSAGE_BROKER, "jms");

  public JMSDecorator(
      String resourcePrefix, String spanKind, CharSequence spanType, String serviceName) {
    this.resourcePrefix = resourcePrefix;

    this.queueTempResourceName = UTF8BytesString.create(resourcePrefix + "Temporary Queue");
    this.topicTempResourceName = UTF8BytesString.create(resourcePrefix + "Temporary Topic");

    this.queueResourceJoiner = QUEUE_JOINER.curry(resourcePrefix);
    this.topicResourceJoiner = TOPIC_JOINER.curry(resourcePrefix);

    this.spanKind = spanKind;
    this.spanType = spanType;
    this.serviceName = serviceName;
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

  public void onConsume(AgentSpan span, Message message, CharSequence resourceName) {
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
  }

  public void onProduce(AgentSpan span, CharSequence resourceName) {
    if (null != resourceName) {
      span.setResourceName(resourceName);
    }
  }

  public void onTimeInQueue(
      final AgentSpan span, final CharSequence resourceName, final String serviceName) {
    if (null != resourceName) {
      span.setResourceName(resourceName);
    }
    if (null != serviceName) {
      span.setServiceName(serviceName);
    }
  }

  private static final String TIBCO_TMP_PREFIX = "$TMP$";

  public CharSequence toResourceName(String destinationName, boolean isQueue) {
    if (null == destinationName) {
      return isQueue ? queueTempResourceName : topicTempResourceName;
    }
    Function<CharSequence, CharSequence> joiner =
        isQueue ? queueResourceJoiner : topicResourceJoiner;
    // some systems may have queues and topics with the same name - since we won't know which was
    // cached first we check the character after the initial prefix to see if it's Q (for Queue) -
    // if that's what we expect we can use the cached value, otherwise generate the correct name
    CharSequence resourceName = resourceNameCache.computeIfAbsent(destinationName, joiner);
    if ((resourceName.charAt(resourcePrefix.length()) == 'Q') == isQueue) {
      return resourceName;
    }
    return joiner.apply(destinationName);
  }

  public String getDestinationName(Destination destination) {
    String name = null;
    try {
      if (destination instanceof Queue) {
        if (!(destination instanceof TemporaryQueue)) {
          name = ((Queue) destination).getQueueName();
        }
      }
      if (destination instanceof Topic) {
        if (!(destination instanceof TemporaryTopic)) {
          name = ((Topic) destination).getTopicName();
        }
      }
    } catch (Exception ignored) {
    }
    return null != name && !name.startsWith(TIBCO_TMP_PREFIX) ? name : null;
  }
}
