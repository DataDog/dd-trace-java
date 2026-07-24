package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.R2DBC_BATCH;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Publisher;

public class BatchInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "io.r2dbc.spi.Batch";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("io.r2dbc.spi.Batch"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("execute")).and(takesArguments(0)),
        BatchInstrumentation.class.getName() + "$BatchExecuteAdvice");
  }

  public static class BatchExecuteAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter() {
      AgentSpan span = startSpan("r2dbc", R2DBC_BATCH);
      DECORATE.afterStart(span);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return(readOnly = false) Publisher<?> publisher,
        @Advice.Thrown final Throwable throwable) {
      AgentSpan span = scope.span();
      if (throwable != null) {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        scope.close();
        span.finish();
      } else {
        publisher = new TracingPublisher<>(publisher, span);
        scope.close();
      }
    }
  }
}
