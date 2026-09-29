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
import datadog.trace.agent.tooling.muzzle.Reference;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class DurableOrchestrationMiddlewareInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final Reference MIDDLEWARE_REFERENCE =
      new Reference.Builder(
              "com.microsoft.durabletask.azurefunctions.internal.middleware.OrchestrationMiddleware")
          .build();

  public DurableOrchestrationMiddlewareInstrumentation() {
    super("azure-functions");
  }

  @Override
  public String instrumentedType() {
    return "com.microsoft.durabletask.azurefunctions.internal.middleware.OrchestrationMiddleware";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DurableFunctionsDecorator",
      packageName + ".DurableFunctionsUtils",
      packageName + ".TraceContextExtractAdapter"
    };
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {MIDDLEWARE_REFERENCE};
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
    public static ContextScope onEnter(@Advice.Argument(0) MiddlewareContext context) {
      return DurableFunctionsUtils.activateTraceContext(context);
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Enter ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
