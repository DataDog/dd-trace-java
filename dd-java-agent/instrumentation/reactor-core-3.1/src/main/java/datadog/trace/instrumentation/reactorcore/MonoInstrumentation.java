package datadog.trace.instrumentation.reactorcore;

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
import net.bytebuddy.asm.Advice;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;

public final class MonoInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "reactor.core.publisher.Mono";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$CaptureParentSpanAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("subscribe"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("reactor.core.CoreSubscriber"))),
        getClass().getName() + "$PropagateParentSpanAdvice");
  }

  public static class CaptureParentSpanAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onConstruct(@Advice.This final Mono<?> mono) {
      Context parentContext = currentContext();
      if (parentContext != rootContext()) {
        InstrumentationContext.get(Mono.class, Context.class).put(mono, parentContext);
      }
    }
  }

  public static class PropagateParentSpanAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onSubscribe(
        @Advice.This final Mono<?> mono,
        @Advice.Argument(value = 0, readOnly = false) CoreSubscriber<?> subscriber) {
      if (subscriber != null) {
        Context parentContext = InstrumentationContext.get(Mono.class, Context.class).get(mono);
        if (parentContext != null) {
          // wrap the subscriber so spans from its events treat the captured span as their parent
          subscriber = new TracingCoreSubscriber<>(subscriber, parentContext);
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
