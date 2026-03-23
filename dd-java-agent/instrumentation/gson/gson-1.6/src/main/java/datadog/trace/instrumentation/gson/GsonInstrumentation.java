package datadog.trace.instrumentation.gson;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.gson.GsonDecorator.DECORATE;
import static datadog.trace.instrumentation.gson.GsonDecorator.GSON_FROM_JSON;
import static datadog.trace.instrumentation.gson.GsonDecorator.GSON_TO_JSON;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class GsonInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public GsonInstrumentation() {
    super("gson");
  }

  @Override
  public String instrumentedType() {
    return "com.google.gson.Gson";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".GsonDecorator", packageName + ".GsonHelper"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Instrument toJson methods
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("toJson")).and(returns(String.class)),
        GsonInstrumentation.class.getName() + "$ToJsonAdvice");

    // Instrument fromJson methods
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("fromJson")).and(takesArgument(0, String.class)),
        GsonInstrumentation.class.getName() + "$FromJsonAdvice");
  }

  public static class ToJsonAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter() {
      // Use CallDepthThreadLocalMap to avoid recursive instrumentation
      final int callDepth = GsonHelper.incrementCallDepth();
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(GSON_TO_JSON);
      DECORATE.afterStart(span);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }

      try {
        final AgentSpan span = scope.span();
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
      } finally {
        scope.close();
        GsonHelper.resetCallDepth();
      }
    }
  }

  public static class FromJsonAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter() {
      // Use CallDepthThreadLocalMap to avoid recursive instrumentation
      final int callDepth = GsonHelper.incrementCallDepth();
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(GSON_FROM_JSON);
      DECORATE.afterStart(span);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }

      try {
        final AgentSpan span = scope.span();
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
      } finally {
        scope.close();
        GsonHelper.resetCallDepth();
      }
    }
  }
}
