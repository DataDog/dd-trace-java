package datadog.trace.instrumentation.googlepubsub;

import com.google.pubsub.v1.PubsubMessage;
import datadog.trace.api.Config;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.MessagingClientDecorator;

public class PubSubDecorator extends MessagingClientDecorator {
  private static final String PUBSUB = "google-pubsub";
  public static final CharSequence JAVA_PUBSUB = UTF8BytesString.create("java-google-pubsub");
  public static final CharSequence PUBSUB_CONSUME =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().inboundOperation(PUBSUB));
  public static final CharSequence PUBSUB_PRODUCE =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().outboundOperation(PUBSUB));
  private final String spanKind;
  private final CharSequence spanType;
  private final String serviceName;

  private static final DDCache<CharSequence, CharSequence> PRODUCER_RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final Functions.Prefix PRODUCER_PREFIX = new Functions.Prefix("Produce Topic ");
  private static final DDCache<CharSequence, CharSequence> CONSUMER_RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final Functions.Prefix CONSUMER_PREFIX = new Functions.Prefix("Consume Topic ");

  private static final String LOCAL_SERVICE_NAME = Config.get().getServiceName();

  public static final PubSubDecorator PRODUCER_DECORATE =
      new PubSubDecorator(
          Tags.SPAN_KIND_PRODUCER, InternalSpanTypes.MESSAGE_PRODUCER, LOCAL_SERVICE_NAME);

  public static final PubSubDecorator CONSUMER_DECORATE =
      new PubSubDecorator(
          Tags.SPAN_KIND_CONSUMER, InternalSpanTypes.MESSAGE_CONSUMER, LOCAL_SERVICE_NAME);

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

  public void onConsume(final AgentSpan span, final PubsubMessage record, final String topic) {
    if (record != null) {
      span.setResourceName(CONSUMER_RESOURCE_NAME_CACHE.computeIfAbsent(topic, CONSUMER_PREFIX));
    }
  }

  public void onProduce(final AgentSpan span, final PubsubMessage record, final String topic) {
    if (record != null) {
      span.setResourceName(PRODUCER_RESOURCE_NAME_CACHE.computeIfAbsent(topic, PRODUCER_PREFIX));
    }
  }
}
