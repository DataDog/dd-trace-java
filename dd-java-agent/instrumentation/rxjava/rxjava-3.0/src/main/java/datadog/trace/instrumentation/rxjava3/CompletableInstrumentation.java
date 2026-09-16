package datadog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.currentContext;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.rootContext;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableObserver;
import net.bytebuddy.asm.Advice;

public final class CompletableInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.reactivex.rxjava3.core.Completable";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$CaptureParentSpanAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("subscribe"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("io.reactivex.rxjava3.core.CompletableObserver"))),
        getClass().getName() + "$PropagateParentSpanAdvice");
  }

  public static class CaptureParentSpanAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onConstruct(@Advice.This final Completable completable) {
      Context parentContext = currentContext();
      if (parentContext != rootContext()) {
        InstrumentationContext.get(Completable.class, Context.class)
            .put(completable, parentContext);
      }
    }
  }

  public static class PropagateParentSpanAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onSubscribe(
        @Advice.This final Completable completable,
        @Advice.Argument(value = 0, readOnly = false) CompletableObserver observer) {
      if (observer != null) {
        Context parentContext =
            InstrumentationContext.get(Completable.class, Context.class).get(completable);
        if (parentContext != null) {
          observer = new TracingCompletableObserver(observer, parentContext);
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
