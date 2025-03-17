package datadog.trace.instrumentation.mqttv5;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mqttv5.UserPropertyExtractAdapter.GETTER;
import static datadog.trace.instrumentation.mqttv5.UserPropertyInjectAdapter.SETTER;

import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

public class MqttDecorator extends ClientDecorator {
  public static final boolean MQTT_LEGACY_TRACING =
      SpanNaming.instance().namingSchema().allowInferredServices()
          && Config.get().isLegacyTracingEnabled(true, "mqtt");
  public static final String INSTRUMENTATION = "mqttv5";
  public static final MqttDecorator PUBLISH_DECORATOR =
      new MqttDecorator(
          Tags.SPAN_KIND_PRODUCER,
          InternalSpanTypes.MESSAGE_PRODUCER,
          SpanNaming.instance()
              .namingSchema()
              .messaging()
              .inboundService("mqtt", MQTT_LEGACY_TRACING));

  public static final MqttDecorator SUBSCRIBE_DECORATOR =
      new MqttDecorator(
          Tags.SPAN_KIND_CONSUMER,
          InternalSpanTypes.MESSAGE_CONSUMER,
          SpanNaming.instance()
              .namingSchema()
              .messaging()
              .inboundService("mqtt", MQTT_LEGACY_TRACING));
  private final String spanKind;
  private final CharSequence spanType;
  private final Supplier<String> serviceNameSupplier;

  public static final String TAG_TOPIC_KEY = "mqtt.topic";

  public MqttDecorator(
      String spanKind, CharSequence spanType, Supplier<String> serviceNameSupplier) {
    this.spanKind = spanKind;
    this.spanType = spanType;
    this.serviceNameSupplier = serviceNameSupplier;
  }

  @Override
  protected String service() {
    return serviceNameSupplier.get();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {INSTRUMENTATION};
  }

  @Override
  protected CharSequence spanType() {
    return spanType;
  }

  @Override
  protected String spanKind() {
    return spanKind;
  }

  @Override
  protected CharSequence component() {
    return INSTRUMENTATION;
  }

  public AgentSpan createSpan(String topic, MqttMessage message) {
    AgentSpan span = startSpan(topic);
    afterStart(span);
    span.setResourceName("/publish/" + topic);

    MqttProperties properties = message.getProperties();
    List<UserProperty> userProperties = new ArrayList<>();
    defaultPropagator().inject(span, userProperties, SETTER);
    if (properties == null) {
      properties = new MqttProperties();
    }
    properties.setUserProperties(userProperties);
    message.setProperties(properties);
    span.setTag(TAG_TOPIC_KEY, topic);
    return span;
  }

  public AgentSpan createCallBackSpan(String topic, MqttMessage message) {
    MqttProperties properties = message.getProperties();
    AgentSpanContext parentContext =
        extractContextAndGetSpanContext(properties.getUserProperties(), GETTER);
    AgentSpan span = startSpan(topic, parentContext);
    afterStart(span);
    span.setResourceName("/subscribe/" + topic);
    span.setTag(TAG_TOPIC_KEY, topic);
    return span;
  }
}
