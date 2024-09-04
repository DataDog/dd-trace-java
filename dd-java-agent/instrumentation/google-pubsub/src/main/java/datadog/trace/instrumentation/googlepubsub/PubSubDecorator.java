package datadog.trace.instrumentation.googlepubsub;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_IN;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.SUBSCRIPTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;

import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.PubsubMessage;
import datadog.trace.api.Config;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.MessagingClientDecorator;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PubSubDecorator extends MessagingClientDecorator {
  private static class RegexExtractor implements Function<CharSequence, CharSequence> {
    private final Pattern pattern;
    private final int group;

    public RegexExtractor(final String regex, final int group) {
      pattern = Pattern.compile(regex);
      this.group = group;
    }

    @Override
    public CharSequence apply(CharSequence input) {
      final Matcher matcher = pattern.matcher(input);
      if (matcher.matches() && group <= matcher.groupCount()) {
        return matcher.group(group);
      }
      return input;
    }
  }

  private static final String PUBSUB = "google-pubsub";
  public static final CharSequence JAVA_PUBSUB = UTF8BytesString.create("java-google-pubsub");
  public static final CharSequence PUBSUB_CONSUME =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().inboundOperation(PUBSUB));
  public static final CharSequence PUBSUB_PRODUCE =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().outboundOperation(PUBSUB));

  private static final DDCache<CharSequence, CharSequence> TOPIC_NAME_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final DDCache<CharSequence, CharSequence> SUBSCRIPTION_NAME_CACHE =
      DDCaches.newFixedSizeCache(32);

  private static final DDCache<CharSequence, CharSequence> PRODUCER_RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final DDCache<CharSequence, CharSequence> CONSUMER_RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final Functions.Prefix PRODUCER_PREFIX = new Functions.Prefix("Produce Topic ");
  private static final Functions.Prefix CONSUMER_PREFIX =
      new Functions.Prefix("Consume Subscription ");

  private static final Function<CharSequence, CharSequence> TOPIC_EXTRACTION_FUNCTION =
      new RegexExtractor("^projects/(.+)/topics/(.+)$", 2).andThen(UTF8BytesString::create);
  private static final Function<CharSequence, CharSequence> SUBSCRIPTION_EXTRACTION_FUNCTION =
      new RegexExtractor("^projects/(.+)/subscriptions/(.+)$", 2).andThen(UTF8BytesString::create);

  public static final PubSubDecorator PRODUCER_DECORATE =
      new PubSubDecorator(
          Tags.SPAN_KIND_PRODUCER,
          InternalSpanTypes.MESSAGE_PRODUCER,
          SpanNaming.instance()
              .namingSchema()
              .messaging()
              .outboundService(PUBSUB, Config.get().isGooglePubSubLegacyTracingEnabled()));

  public static final PubSubDecorator CONSUMER_DECORATE =
      new PubSubDecorator(
          Tags.SPAN_KIND_CONSUMER,
          InternalSpanTypes.MESSAGE_CONSUMER,
          SpanNaming.instance()
              .namingSchema()
              .messaging()
              .inboundService(PUBSUB, Config.get().isGooglePubSubLegacyTracingEnabled()));
  private final String spanKind;
  private final CharSequence spanType;
  private final String serviceName;

  protected PubSubDecorator(String spanKind, CharSequence spanType, String serviceName) {
    this.spanKind = spanKind;
    this.spanType = spanType;
    this.serviceName = serviceName;
  }

  @Override
  protected CharSequence spanType() {
    return spanType;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"google-pubsub"};
  }

  @Override
  protected String service() {
    return serviceName;
  }

  @Override
  protected CharSequence component() {
    return JAVA_PUBSUB;
  }

  @Override
  protected String spanKind() {
    return spanKind;
  }

  public AgentSpan onConsume(final PubsubMessage message, final String subscription) {
    final AgentSpan.Context spanContext =
        propagate().extract(message, TextMapExtractAdapter.GETTER);
    final AgentSpan span = startSpan(PUBSUB_CONSUME, spanContext);
    final CharSequence parsedSubscription = extractSubscription(subscription);
    final LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>(3);
    sortedTags.put(DIRECTION_TAG, DIRECTION_IN);
    sortedTags.put(SUBSCRIPTION_TAG, parsedSubscription.toString());
    sortedTags.put(TYPE_TAG, "google-pubsub");
    final Timestamp publishTime = message.getPublishTime();
    // FIXME: use full nanosecond resolution when this method will accept nanos
    AgentTracer.get()
        .getDataStreamsMonitoring()
        .setCheckpoint(
            span,
            sortedTags,
            publishTime.getSeconds() * 1_000 + publishTime.getNanos() / (int) 1e6,
            message.getSerializedSize());
    afterStart(span);
    span.setResourceName(
        CONSUMER_RESOURCE_NAME_CACHE.computeIfAbsent(parsedSubscription, CONSUMER_PREFIX));
    return span;
  }

  public void onProduce(final AgentSpan span, final CharSequence topic) {
    span.setResourceName(PRODUCER_RESOURCE_NAME_CACHE.computeIfAbsent(topic, PRODUCER_PREFIX));
  }

  public CharSequence extractTopic(String fullTopic) {
    return TOPIC_NAME_CACHE.computeIfAbsent(fullTopic, TOPIC_EXTRACTION_FUNCTION);
  }

  public CharSequence extractSubscription(String fullSubscription) {
    return SUBSCRIPTION_NAME_CACHE.computeIfAbsent(
        fullSubscription, SUBSCRIPTION_EXTRACTION_FUNCTION);
  }
}
