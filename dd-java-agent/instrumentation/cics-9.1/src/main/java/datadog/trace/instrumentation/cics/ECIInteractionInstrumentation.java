package datadog.trace.instrumentation.cics;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.cics.CicsDecorator.DECORATE;
import static datadog.trace.instrumentation.cics.CicsDecorator.ECI_EXECUTE_OPERATION;

import com.ibm.connector2.cics.ECIInteraction;
import com.ibm.connector2.cics.ECIInteractionSpec;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

public final class ECIInteractionInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "com.ibm.connector2.cics.ECIInteraction";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(named("execute"), getClass().getName() + "$ExecuteAdvice");
  }

  public static class ExecuteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(@Advice.Argument(0) final Object spec) {
      // Coordinating with JavaGatewayInterfaceInstrumentation
      CallDepthThreadLocalMap.incrementCallDepth(ECIInteraction.class);

      if (!(spec instanceof ECIInteractionSpec)) {
        return null;
      }

      AgentSpan span = startSpan(ECI_EXECUTE_OPERATION);
      DECORATE.afterStart(span);
      DECORATE.onECIInteraction(span, (ECIInteractionSpec) spec);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      CallDepthThreadLocalMap.decrementCallDepth(ECIInteraction.class);

      if (null != scope) {
        DECORATE.onError(scope.span(), throwable);
        DECORATE.beforeFinish(scope.span());
        scope.span().finish();
        scope.close();
      }
    }
  }
}
