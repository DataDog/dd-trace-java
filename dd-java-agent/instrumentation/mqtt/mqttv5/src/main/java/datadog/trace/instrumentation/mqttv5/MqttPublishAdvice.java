package datadog.trace.instrumentation.mqttv5;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.paho.mqttv5.client.internal.MqttConnectionState;
import org.eclipse.paho.mqttv5.common.MqttMessage;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.mqttv5.MqttDecorator.PUBLISH_DECORATOR;

public class MqttPublishAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope execute(
      @Advice.FieldValue("serverURI") String serverURI, @Advice.FieldValue("mqttConnection") MqttConnectionState mqttConnection,
      @Advice.Argument(0) String topic,
      @Advice.Argument(1) MqttMessage message) {
    AgentSpan span = PUBLISH_DECORATOR.createSpan(topic, message);
    span.setTag("mqtt.serverURI", serverURI);
    span.setTag("mqtt.clientid", mqttConnection.getClientId());
    span.setTag("mqtt.message.qos", message.getQos());
    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void exit(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    PUBLISH_DECORATOR.onError(scope.span(), throwable);
    PUBLISH_DECORATOR.beforeFinish(scope.span());
    scope.close();
    scope.span().finish();
  }
}
