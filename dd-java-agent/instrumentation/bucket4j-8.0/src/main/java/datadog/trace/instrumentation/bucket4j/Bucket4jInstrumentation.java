package datadog.trace.instrumentation.bucket4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.bucket4j.Bucket4jDecorator.DECORATE;
import static datadog.trace.instrumentation.bucket4j.Bucket4jDecorator.TRY_CONSUME;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.github.bucket4j.Bucket;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class Bucket4jInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public Bucket4jInstrumentation() {
    super("bucket4j");
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.github.bucket4j.Bucket";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("tryConsume"))
            .and(takesArguments(1))
            .and(takesArgument(0, long.class)),
        Bucket4jInstrumentation.class.getName() + "$TryConsumeAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".Bucket4jDecorator"};
  }

  public static class TryConsumeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter() {
      final AgentSpan span = startSpan("bucket4j", TRY_CONSUME, null);
      DECORATE.afterStart(span);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter final AgentScope scope,
        @Advice.This final Bucket bucket,
        @Advice.Argument(0) final long tokens,
        @Advice.Return final boolean consumed,
        @Advice.Thrown final Throwable throwable) {
      final AgentSpan span = scope.span();
      if (throwable != null) {
        DECORATE.onError(span, throwable);
      }
      DECORATE.onConsume(span, bucket, tokens, consumed);
      DECORATE.beforeFinish(span);
      span.finish();
      scope.close();
    }
  }
}
