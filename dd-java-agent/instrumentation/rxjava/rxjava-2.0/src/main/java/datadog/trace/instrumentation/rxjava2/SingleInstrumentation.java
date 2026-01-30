package datadog.trace.instrumentation.rxjava2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge;
import io.reactivex.Single;
import io.reactivex.SingleObserver;
import net.bytebuddy.asm.Advice;

public final class SingleInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.reactivex.Single";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$CaptureParentSpanAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("subscribe"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("io.reactivex.SingleObserver"))),
        getClass().getName() + "$PropagateParentSpanAdvice");
  }

  public static class CaptureParentSpanAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onConstruct(@Advice.This final Single<?> single) {
      Context parentSpan = Java8BytecodeBridge.getCurrentContext();
      if (parentSpan != null) {
        InstrumentationContext.get(Single.class, Context.class).put(single, parentSpan);
      }
    }
  }

  public static class PropagateParentSpanAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onSubscribe(
        @Advice.This final Single<?> single,
        @Advice.Argument(value = 0, readOnly = false) SingleObserver<?> observer) {
      if (observer != null) {
        Context parentContext = InstrumentationContext.get(Single.class, Context.class).get(single);
        if (parentContext != null && parentContext != Java8BytecodeBridge.getRootContext()) {
          // wrap the observer so spans from its events treat the captured span as their parent
          observer = new TracingSingleObserver<>(observer, parentContext);
          // attach the context here in case additional observers are created during subscribe
          return parentContext.attach();
        }
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeScope(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
