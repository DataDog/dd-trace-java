package datadog.trace.instrumentation.grpc.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.grpc.client.GrpcClientDecorator.DECORATE;
import static datadog.trace.instrumentation.grpc.client.GrpcClientDecorator.GRPC_MESSAGE;
import static datadog.trace.instrumentation.grpc.client.GrpcClientDecorator.OPERATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Collections;
import net.bytebuddy.asm.Advice;

public final class MessagesAvailableInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.grpc.internal.ClientCallImpl$ClientStreamListenerImpl$1MessagesAvailable",
      "io.grpc.internal.ClientCallImpl$ClientStreamListenerImpl$1MessageRead"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Capture");
    if (InstrumenterConfig.get()
        .isIntegrationEnabled(Collections.singleton("grpc-message"), false)) {
      transformer.applyAdvice(named("runInContext"), getClass().getName() + "$ReceiveMessages");
    }
  }

  public static final class Capture {
    @Advice.OnMethodExit
    public static void capture(@Advice.This Runnable task) {
      AdviceUtils.capture(InstrumentationContext.get(Runnable.class, State.class), task);
    }
  }

  public static final class ReceiveMessages {
    @Advice.OnMethodEnter
    public static AgentScope before() {
      AgentSpan clientSpan = activeSpan();
      if (clientSpan != null && OPERATION_NAME.equals(clientSpan.getOperationName())) {
        AgentSpan messageSpan =
            startSpan(GRPC_MESSAGE).setTag("message.type", clientSpan.getTag("response.type"));
        DECORATE.afterStart(messageSpan);
        return activateSpan(messageSpan);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      if (null != scope) {
        scope.span().finish();
        scope.close();
      }
    }
  }
}
