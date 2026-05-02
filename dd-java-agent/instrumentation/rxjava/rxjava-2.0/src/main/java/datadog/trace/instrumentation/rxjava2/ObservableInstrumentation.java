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
import io.reactivex.Observable;
import io.reactivex.Observer;
import net.bytebuddy.asm.Advice;

public final class ObservableInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "io.reactivex.Observable";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$CaptureParentSpanAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("subscribe"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("io.reactivex.Observer"))),
        getClass().getName() + "$PropagateParentSpanAdvice");
  }

  public static class CaptureParentSpanAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onConstruct(@Advice.This final Observable<?> observable) {
      Context parentContext = Java8BytecodeBridge.getCurrentContext();
      if (parentContext != null) {
        InstrumentationContext.get(Observable.class, Context.class).put(observable, parentContext);
      }
    }
  }

  public static class PropagateParentSpanAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onSubscribe(
        @Advice.This final Observable<?> observable,
        @Advice.Argument(value = 0, readOnly = false) Observer<?> observer) {
      if (observer != null) {
        Context parentContext =
            InstrumentationContext.get(Observable.class, Context.class).get(observable);
        if (parentContext != null && parentContext != Java8BytecodeBridge.getRootContext()) {
          // wrap the observer so spans from its events treat the captured span as their parent
          observer = new TracingObserver<>(observer, parentContext);
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
