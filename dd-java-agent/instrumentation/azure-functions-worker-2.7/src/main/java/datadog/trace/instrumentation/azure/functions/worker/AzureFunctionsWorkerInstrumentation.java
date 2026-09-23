package datadog.trace.instrumentation.azure.functions.worker;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromScope;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsDecorator.AZURE_FUNCTIONS_REQUEST;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.microsoft.azure.functions.TraceContext;
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareContext;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
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
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DurableFunctionsDecorator",
      packageName + ".DurableFunctionsUtils",
      packageName + ".TraceContextExtractAdapter"
    };
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
    public static ContextScope onEnter(@Advice.Argument(0) MiddlewareContext context) {
      final String trigger = DurableFunctionsUtils.getTrigger(context);
      if (trigger == null) {
        return null;
      }
      if ("DurableOrchestration".equals(trigger)
          && !DurableFunctionsUtils.shouldTraceOrchestration(context)) {
        return null;
      }

      final TraceContext traceContext = context.getTraceContext();
      AgentSpanContext.Extracted parent =
          traceContext == null
              ? null
              : extractContextAndGetSpanContext(traceContext, TraceContextExtractAdapter.GETTER);
      parent = DurableFunctionsUtils.reconcileSamplingPriority(parent, traceContext);

      final AgentSpan span = startSpan("azure-functions", AZURE_FUNCTIONS_REQUEST, parent);
      DECORATE.afterStart(span);
      DECORATE.onInvoke(span, context.getFunctionName(), trigger);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter ContextScope scope, @Advice.Thrown Throwable throwable) {
      if (scope != null) {
        final AgentSpan span = spanFromScope(scope);
        if (!DurableFunctionsUtils.isReplayControlFlow(throwable)) {
          DECORATE.onError(span, throwable);
        }
        DECORATE.beforeFinish(span);
        scope.close();
        span.finish();
      }
    }
  }
}
