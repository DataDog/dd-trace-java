package datadog.trace.instrumentation.googlepubsub;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.get;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_IN;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.GROUP_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;

import com.google.pubsub.v1.PubsubMessage;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentDataStreamsMonitoring;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
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

  private static final DDCache<CharSequence, CharSequence> PRODUCER_RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final Functions.Prefix PRODUCER_PREFIX =
      new Functions.Prefix("Produce Topic ", new RegexExtractor("^projects/(.+)/topics/(.+)$", 2));
  private static final DDCache<CharSequence, CharSequence> CONSUMER_RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final Functions.Prefix CONSUMER_PREFIX =
      new Functions.Prefix(
          "Consume Subscription ", new RegexExtractor("^projects/(.+)/subscriptions/(.+)$", 2));

  public static final PubSubDecorator PRODUCER_DECORATE =
      new PubSubDecorator(
          Tags.SPAN_KIND_PRODUCER,
          InternalSpanTypes.MESSAGE_PRODUCER,
          SpanNaming.instance().namingSchema().messaging().outboundService(PUBSUB, true));

  public static final PubSubDecorator CONSUMER_DECORATE =
      new PubSubDecorator(
          Tags.SPAN_KIND_CONSUMER,
          InternalSpanTypes.MESSAGE_CONSUMER,
          SpanNaming.instance().namingSchema().messaging().inboundService(PUBSUB, true));

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
    final AgentDataStreamsMonitoring dataStreamsMonitoring = get().getDataStreamsMonitoring();
    final PathwayContext pathwayContext = dataStreamsMonitoring.newPathwayContext();

    // FIXME?: in the pubsub model, the receiver only knows the subscription and not the topic name.
    // We might know this by calling an admin API but for the moment let's avoid extra overhead

    final LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>(3);
    sortedTags.put(DIRECTION_TAG, DIRECTION_IN);
    sortedTags.put(GROUP_TAG, subscription);
    sortedTags.put(TYPE_TAG, "google-pubsub");
    // TODO: should we calculate the arrival time (now - publish_time) and use it?
    pathwayContext.setCheckpoint(sortedTags, dataStreamsMonitoring::add);
    if (!span.context().getPathwayContext().isStarted()) {
      span.context().mergePathwayContext(pathwayContext);
    }
    afterStart(span);
    span.setResourceName(
        CONSUMER_RESOURCE_NAME_CACHE.computeIfAbsent(subscription, CONSUMER_PREFIX));
    return span;
  }

  public void onProduce(final AgentSpan span, final PubsubMessage record, final String topic) {
    span.setResourceName(PRODUCER_RESOURCE_NAME_CACHE.computeIfAbsent(topic, PRODUCER_PREFIX));
  }
}
