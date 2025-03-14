package datadog.trace.instrumentation.mqttv5;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.paho.mqttv5.common.MqttMessage;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.mqttv5.MqttDecorator.SUBSCRIBE_DECORATOR;

public class MqttCallBackAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope execute(
      @Advice.Argument(0) String topic,
      @Advice.Argument(1) MqttMessage message) {
    AgentSpan span = SUBSCRIBE_DECORATOR.createCallBackSpan(topic, message);
    AgentScope agentScope = activateSpan(span);

    return agentScope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void exit(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    SUBSCRIBE_DECORATOR.onError(scope.span(), throwable);
    SUBSCRIBE_DECORATOR.beforeFinish(scope.span());
    scope.close();
    scope.span().finish();
  }
}
