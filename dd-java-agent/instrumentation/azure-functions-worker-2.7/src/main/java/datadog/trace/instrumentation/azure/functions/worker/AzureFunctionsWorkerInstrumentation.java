package datadog.trace.instrumentation.azure.functions.worker;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromScope;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsDecorator.DECORATE;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsDecorator.ORCHESTRATION_TRIGGER;
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
public final class AzureFunctionsWorkerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public AzureFunctionsWorkerInstrumentation() {
    super("azure-functions");
  }

  @Override
  public String instrumentedType() {
    return "com.microsoft.azure.functions.worker.chain.FunctionExecutionMiddleware";
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
        AzureFunctionsWorkerInstrumentation.class.getName() + "$InvokeAdvice");
  }

  public static class InvokeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(
        @Advice.Argument(0) MiddlewareContext context,
        @Advice.Local("trigger") String trigger,
        @Advice.Local("orchestrationSpan") AgentSpan orchestrationSpan,
        @Advice.Local("startTimeMicros") long startTimeMicros) {
      trigger = DurableFunctionsUtils.getTrigger(context);
      if (trigger == null) {
        return null;
      }
      if (ORCHESTRATION_TRIGGER.equals(trigger)) {
        orchestrationSpan = DurableFunctionsUtils.onOrchestrationInvoke();
        if (orchestrationSpan == null) {
          startTimeMicros = DurableFunctionsUtils.nowMicros();
        }
        return null;
      }

      return DurableFunctionsUtils.startSpanScope(context, trigger);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) MiddlewareContext context,
        @Advice.Enter ContextScope scope,
        @Advice.Local("trigger") String trigger,
        @Advice.Local("orchestrationSpan") AgentSpan orchestrationSpan,
        @Advice.Local("startTimeMicros") long startTimeMicros,
        @Advice.Thrown Throwable throwable) {
      if (orchestrationSpan != null) {
        if (throwable != null && !DurableFunctionsUtils.isReplayControlFlow(throwable)) {
          DECORATE.onInvocationError(orchestrationSpan, throwable);
        }
        return;
      }

      if (scope == null) {
        if (throwable != null
            && ORCHESTRATION_TRIGGER.equals(trigger)
            && !DurableFunctionsUtils.isReplayControlFlow(throwable)) {
          final AgentSpan span =
              DurableFunctionsUtils.startOrchestrationErrorSpan(context, startTimeMicros);
          final DurableOrchestrationState state = DurableOrchestrationState.current();
          DurableFunctionsUtils.recordOrchestrationError(span, throwable, state);
        }
        return;
      }
      final AgentSpan span = spanFromScope(scope);
      try {
        if (!DurableFunctionsUtils.isReplayControlFlow(throwable)) {
          DECORATE.onInvocationError(span, throwable);
        }
        DECORATE.beforeFinish(span);
      } finally {
        try {
          scope.close();
        } finally {
          span.finish();
        }
      }
    }
  }
}
