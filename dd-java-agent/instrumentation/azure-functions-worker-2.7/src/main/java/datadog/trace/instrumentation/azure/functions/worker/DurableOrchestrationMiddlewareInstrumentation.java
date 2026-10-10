package datadog.trace.instrumentation.azure.functions.worker;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareContext;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.azure.DurableOrchestrationState;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class DurableOrchestrationMiddlewareInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public DurableOrchestrationMiddlewareInstrumentation() {
    super("azure-functions");
  }

  @Override
  public String instrumentedType() {
    return "com.microsoft.durabletask.azurefunctions.internal.middleware.OrchestrationMiddleware";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("invoke"))
            .and(takesArguments(2))
            .and(
                takesArgument(
                    0,
                    named(
                        "com.microsoft.azure.functions.internal.spi.middleware.MiddlewareContext")))
            .and(
                takesArgument(
                    1,
                    named(
                        "com.microsoft.azure.functions.internal.spi.middleware.MiddlewareChain"))),
        DurableOrchestrationMiddlewareInstrumentation.class.getName() + "$InvokeAdvice");
  }

  public static class InvokeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(
        @Advice.Argument(0) MiddlewareContext context,
        @Advice.Local("startTimeMicros") long startTimeMicros) {
      final ContextScope scope = DurableFunctionsUtils.activateOrchestrationContext(context);
      if (scope != null) {
        startTimeMicros = DurableFunctionsUtils.nowMicros();
      }
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) MiddlewareContext context,
        @Advice.Enter ContextScope scope,
        @Advice.Local("startTimeMicros") long startTimeMicros,
        @Advice.Thrown Throwable throwable) {
      if (scope == null) {
        return;
      }
      try {
        final DurableOrchestrationState state = DurableOrchestrationState.current();
        if (throwable != null && state != null && !state.errorRecorded()) {
          final AgentSpan span =
              DurableFunctionsUtils.startOrchestrationErrorSpan(context, startTimeMicros);
          DurableFunctionsUtils.recordOrchestrationError(span, throwable, state);
        }
      } finally {
        scope.close();
      }
    }
  }
}
