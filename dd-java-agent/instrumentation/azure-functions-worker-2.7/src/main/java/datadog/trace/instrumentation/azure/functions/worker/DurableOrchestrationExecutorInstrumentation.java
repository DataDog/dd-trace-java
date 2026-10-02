package datadog.trace.instrumentation.azure.functions.worker;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromScope;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsDecorator.DECORATE;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsUtils.startOrchestrationSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.TaskFailureDetails;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.azure.DurableOrchestrationState;
import java.util.List;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class DurableOrchestrationExecutorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public DurableOrchestrationExecutorInstrumentation() {
    super("azure-functions");
  }

  @Override
  public String instrumentedType() {
    return "com.microsoft.durabletask.TaskOrchestrationExecutor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("execute"))
            .and(takesArgument(0, named("java.util.List")))
            .and(takesArgument(1, named("java.util.List"))),
        DurableOrchestrationExecutorInstrumentation.class.getName() + "$ExecuteAdvice");
  }

  public static class ExecuteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(
        @Advice.Argument(0) List<?> pastEvents,
        @Advice.Argument(1) List<?> newEvents,
        @Advice.Local("state") DurableOrchestrationState state,
        @Advice.Local("previousSpan") AgentSpan previousSpan,
        @Advice.Local("startTimeMicros") long startTimeMicros) {
      state = DurableOrchestrationState.current();
      if (state == null) {
        return null;
      }
      startTimeMicros = DurableFunctionsUtils.nowMicros();
      if (!DurableOrchestrationUtils.shouldTrace(pastEvents, newEvents)) {
        return null;
      }
      final ContextScope scope = DurableOrchestrationUtils.startSpanScope(state);
      previousSpan = state.setSpan(spanFromScope(scope));
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter ContextScope scope,
        @Advice.Local("state") DurableOrchestrationState state,
        @Advice.Local("previousSpan") AgentSpan previousSpan,
        @Advice.Local("startTimeMicros") long startTimeMicros,
        @Advice.Thrown Throwable throwable,
        @Advice.Return Object result) {
      if (state == null) {
        return;
      }
      if (scope != null) {
        final AgentSpan span = spanFromScope(scope);
        try {
          final TaskFailureDetails failure =
              throwable == null ? DurableOrchestrationUtils.failureDetails(result) : null;
          DECORATE.onInvocationError(span, throwable);
          if (failure != null) {
            DurableOrchestrationUtils.onOrchestrationFailure(span, failure);
          }
          DECORATE.beforeFinish(span);
        } finally {
          state.setSpan(previousSpan);
          try {
            scope.close();
          } finally {
            span.finish();
            if (span.isError()) {
              state.markErrorRecorded();
            }
          }
        }
        return;
      }
      if (state.errorRecorded()) {
        return;
      }
      final TaskFailureDetails failure =
          throwable == null ? DurableOrchestrationUtils.failureDetails(result) : null;
      if (failure != null || throwable != null) {
        final AgentSpan span = startOrchestrationSpan(state, startTimeMicros);
        DurableOrchestrationUtils.recordSdkOrchestrationError(span, throwable, failure, state);
      }
    }
  }
}
