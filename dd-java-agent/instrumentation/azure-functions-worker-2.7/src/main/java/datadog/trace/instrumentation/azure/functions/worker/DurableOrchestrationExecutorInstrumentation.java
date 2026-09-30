package datadog.trace.instrumentation.azure.functions.worker;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromScope;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.List;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class DurableOrchestrationExecutorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final Reference EXECUTOR_REFERENCE =
      new Reference.Builder("com.microsoft.durabletask.TaskOrchestrationExecutor").build();

  public DurableOrchestrationExecutorInstrumentation() {
    super("azure-functions");
  }

  @Override
  public String instrumentedType() {
    return "com.microsoft.durabletask.TaskOrchestrationExecutor";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DurableFunctionsDecorator",
      packageName + ".DurableOrchestrationState",
      packageName + ".DurableOrchestrationUtils"
    };
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {EXECUTOR_REFERENCE};
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
        @Advice.Local("previousSpan") AgentSpan previousSpan) {
      state = DurableOrchestrationState.current();
      if (state == null || !DurableOrchestrationUtils.shouldTrace(pastEvents, newEvents)) {
        return null;
      }
      final ContextScope scope = DurableOrchestrationUtils.startSpanScope();
      previousSpan = state.setSpan(spanFromScope(scope));
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter ContextScope scope,
        @Advice.Local("state") DurableOrchestrationState state,
        @Advice.Local("previousSpan") AgentSpan previousSpan,
        @Advice.Thrown Throwable throwable) {
      if (scope != null) {
        final AgentSpan span = spanFromScope(scope);
        DECORATE.onInvocationError(span, throwable);
        DECORATE.beforeFinish(span);
        state.setSpan(previousSpan);
        scope.close();
        span.finish();
      }
    }
  }
}
