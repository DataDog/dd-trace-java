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
import reactor.core.publisher.Flux;

public final class FluxInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "reactor.core.publisher.Flux";
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
    public static void onConstruct(@Advice.This final Flux<?> flux) {
      Context parentContext = currentContext();
      if (parentContext != rootContext()) {
        InstrumentationContext.get(Flux.class, Context.class).put(flux, parentContext);
      }
    }
  }

  public static class PropagateParentSpanAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onSubscribe(
        @Advice.This final Flux<?> flux,
        @Advice.Argument(value = 0, readOnly = false) CoreSubscriber<?> subscriber) {
      if (subscriber != null) {
        Context parentContext =
            InstrumentationContext.get(Flux.class, Context.class).get(flux);
        if (parentContext != null) {
          subscriber = new TracingCoreSubscriber<>(subscriber, parentContext);
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
