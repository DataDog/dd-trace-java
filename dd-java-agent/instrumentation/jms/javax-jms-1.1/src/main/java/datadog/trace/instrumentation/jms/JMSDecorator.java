package datadog.trace.instrumentation.jms;

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
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.TemporaryQueue;
import javax.jms.TemporaryTopic;
import javax.jms.Topic;
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

  public static final boolean JMS_LEGACY_TRACING = Config.get().isJmsLegacyTracingEnabled();

  public static final boolean TIME_IN_QUEUE_ENABLED =
      Config.get().isTimeInQueueEnabled(!JMS_LEGACY_TRACING, "jms");
  public static final String JMS_PRODUCED_KEY = "x_datadog_jms_produced";
  public static final String JMS_BATCH_ID_KEY = "x_datadog_jms_batch_id";

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
  private final Supplier<String> serviceNameSupplier;

  public static final JMSDecorator PRODUCER_DECORATE =
      new JMSDecorator(
          "Produced for ",
          Tags.SPAN_KIND_PRODUCER,
          InternalSpanTypes.MESSAGE_PRODUCER,
          SpanNaming.instance()
              .namingSchema()
              .messaging()
              .outboundService("jms", JMS_LEGACY_TRACING));

  public static final JMSDecorator CONSUMER_DECORATE =
      new JMSDecorator(
          "Consumed from ",
          Tags.SPAN_KIND_CONSUMER,
          InternalSpanTypes.MESSAGE_CONSUMER,
          SpanNaming.instance()
              .namingSchema()
              .messaging()
              .inboundService("jms", JMS_LEGACY_TRACING));

  public static final JMSDecorator BROKER_DECORATE =
      new JMSDecorator(
          "",
          Tags.SPAN_KIND_BROKER,
          InternalSpanTypes.MESSAGE_BROKER,
          SpanNaming.instance().namingSchema().messaging().timeInQueueService(JMS.toString()));

  public JMSDecorator(
      String resourcePrefix,
      String spanKind,
      CharSequence spanType,
      Supplier<String> serviceNameSupplier) {
    this.resourcePrefix = resourcePrefix;

    this.queueTempResourceName = UTF8BytesString.create(resourcePrefix + "Temporary Queue");
    this.topicTempResourceName = UTF8BytesString.create(resourcePrefix + "Temporary Topic");

    this.queueResourceJoiner = QUEUE_JOINER.curry(resourcePrefix);
    this.topicResourceJoiner = TOPIC_JOINER.curry(resourcePrefix);

    this.spanKind = spanKind;
    this.spanType = spanType;
    this.serviceNameSupplier = serviceNameSupplier;
  }

  public static void logJMSException(JMSException ex) {
    if (log.isDebugEnabled()) {
      log.debug("JMS exception during instrumentation", ex);
    }
  }

  public static String messageTechnology(Message m) {
    if (null == m) {
      return "null";
    }

    String messageClass = m.getClass().getName();

    if (messageClass.startsWith("com.amazon.sqs")) {
      return "sqs";
    } else if (messageClass.startsWith("com.ibm")) {
      return "ibmmq";
    } else {
      return "unknown";
    }
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jms"};
  }

  @Override
  protected CharSequence spanType() {
    return spanType;
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

  // Pattern to match Kafka Connect schema-derived suffixes like _messagebody_0, _text_0, _bytes_0
  // These suffixes are added by Kafka Connect converters when handling union/optional fields
  private static final java.util.regex.Pattern KAFKA_CONNECT_SCHEMA_SUFFIX_PATTERN =
      java.util.regex.Pattern.compile("_(?:messagebody|text|bytes|map|value)_\\d+$", java.util.regex.Pattern.CASE_INSENSITIVE);

  /**
   * Sanitizes destination names to remove Kafka Connect schema-derived suffixes.
   * When Kafka Connect's IBM MQ connectors are used with schema converters (Protobuf/JSON Schema),
   * union or optional fields may get index suffixes like _messagebody_0 appended to the queue name.
   */
  private static String sanitizeDestinationName(String name) {
    if (name == null) {
      return null;
    }
    return KAFKA_CONNECT_SCHEMA_SUFFIX_PATTERN.matcher(name).replaceFirst("");
  }

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
    if (null != name && !name.startsWith(TIBCO_TMP_PREFIX)) {
      // Sanitize Kafka Connect schema-derived suffixes from queue/topic names
      return sanitizeDestinationName(name);
    }
    return null;
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
