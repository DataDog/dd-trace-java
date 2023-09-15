package datadog.trace.instrumentation.jakarta.jms;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.RECORD_QUEUE_TIME_MS;

import datadog.trace.api.Config;
import datadog.trace.api.Functions.Join;
import datadog.trace.api.Functions.PrefixJoin;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.MessagingClientDecorator;
import jakarta.jms.Destination;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.TemporaryQueue;
import jakarta.jms.TemporaryTopic;
import jakarta.jms.Topic;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JMSDecorator extends MessagingClientDecorator {
  private static final Logger log = LoggerFactory.getLogger(JMSDecorator.class);

  public static final CharSequence JMS = UTF8BytesString.create("jms");
  public static final CharSequence JMS_CONSUME =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().inboundOperation(JMS.toString()));
  public static final CharSequence JMS_PRODUCE =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().outboundOperation(JMS.toString()));
  public static final CharSequence JMS_DELIVER = UTF8BytesString.create("jms.deliver");

  public static final boolean JMS_LEGACY_TRACING = Config.get().isLegacyTracingEnabled(true, "jms");

  public static final boolean TIME_IN_QUEUE_ENABLED =
      Config.get().isTimeInQueueEnabled(!JMS_LEGACY_TRACING, "jms");
  public static final String JMS_PRODUCED_KEY = "x_datadog_jms_produced";
  public static final String JMS_BATCH_ID_KEY = "x_datadog_jms_batch_id";

  private static final Join QUEUE_JOINER = PrefixJoin.of("Queue ");
  private static final Join TOPIC_JOINER = PrefixJoin.of("Topic ");

  private static final String LOCAL_SERVICE_NAME =
      JMS_LEGACY_TRACING ? "jms" : Config.get().getServiceName();

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
          LOCAL_SERVICE_NAME);

  public static final JMSDecorator CONSUMER_DECORATE =
      new JMSDecorator(
          "Consumed from ",
          Tags.SPAN_KIND_CONSUMER,
          InternalSpanTypes.MESSAGE_CONSUMER,
          LOCAL_SERVICE_NAME);

  public static final JMSDecorator BROKER_DECORATE =
      new JMSDecorator(
          "",
          Tags.SPAN_KIND_BROKER,
          InternalSpanTypes.MESSAGE_BROKER,
          SpanNaming.instance().namingSchema().messaging().timeInQueueService(JMS.toString()));

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
    return new String[] {"jakarta-jms"};
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
    } catch (Exception e) {
      log.debug("Unable to get jms timestamp", e);
    }
  }

  public void onProduce(AgentSpan span, CharSequence resourceName) {
    if (null != resourceName) {
      span.setResourceName(resourceName);
    }
  }

  public static boolean canInject(Message message) {
    // JMS->SQS already stores the trace context in 'X-Amzn-Trace-Id' / 'AWSTraceHeader',
    // so skip storing same context again to avoid SQS limit of 10 attributes per message.
    return !message.getClass().getName().startsWith("com.amazon.sqs.javamessaging");
  }

  public void onTimeInQueue(AgentSpan span, CharSequence resourceName, String serviceName) {
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
        // WebLogic mixes all JMS Destination interfaces in a single base type which means we can't
        // rely on instanceof and have to instead check the result of getQueueName vs getTopicName
        if (!(destination instanceof TemporaryQueue) || isWebLogicDestination(destination)) {
          name = ((Queue) destination).getQueueName();
        }
      }
      // check Topic name if Queue name is null because this might be a WebLogic destination
      if (null == name && destination instanceof Topic) {
        if (!(destination instanceof TemporaryTopic) || isWebLogicDestination(destination)) {
          name = ((Topic) destination).getTopicName();
        }
      }
    } catch (Exception e) {
      log.debug("Unable to get jms destination name", e);
    }
    return null != name && !name.startsWith(TIBCO_TMP_PREFIX) ? name : null;
  }

  public boolean isQueue(Destination destination) {
    try {
      // handle WebLogic by treating everything as a Queue unless it's a Topic with a name
      return !(destination instanceof Topic) || null == ((Topic) destination).getTopicName();
    } catch (Exception e) {
      return true; // assume it's a Queue if we can't check the details
    }
  }

  private static boolean isWebLogicDestination(Destination destination) {
    return destination.getClass().getName().startsWith("weblogic.jms.common.");
  }
}
