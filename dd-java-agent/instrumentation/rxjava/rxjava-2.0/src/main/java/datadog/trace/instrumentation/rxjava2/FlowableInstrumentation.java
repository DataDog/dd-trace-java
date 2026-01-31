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
import io.reactivex.Flowable;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Subscriber;

public final class FlowableInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.reactivex.Flowable";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$CaptureParentSpanAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("subscribe"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.reactivestreams.Subscriber"))),
        getClass().getName() + "$PropagateParentSpanAdvice");
  }

  public static class CaptureParentSpanAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onConstruct(@Advice.This final Flowable<?> flowable) {
      Context parentContext = Java8BytecodeBridge.getCurrentContext();
      if (parentContext != null && parentContext != Java8BytecodeBridge.getRootContext()) {
        InstrumentationContext.get(Flowable.class, Context.class).put(flowable, parentContext);
      }
    }
  }

  public static class PropagateParentSpanAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onSubscribe(
        @Advice.This final Flowable<?> flowable,
        @Advice.Argument(value = 0, readOnly = false) Subscriber<?> subscriber) {
      if (subscriber != null) {
        Context parentContext =
            InstrumentationContext.get(Flowable.class, Context.class).get(flowable);
        if (parentContext != null) {
          // wrap the subscriber so spans from its events treat the captured span as their parent
          subscriber = new TracingSubscriber<>(subscriber, parentContext);
          // attach the context here in case additional subscribers are created during subscribe
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
