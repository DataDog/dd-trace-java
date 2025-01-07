package datadog.trace.instrumentation.rxjava2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.reactivex.Maybe;
import io.reactivex.MaybeObserver;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class MaybeInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public MaybeInstrumentation() {
    super("rxjava");
  }

  @Override
  public String instrumentedType() {
    return "io.reactivex.Maybe";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TracingMaybeObserver",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("io.reactivex.Maybe", AgentSpan.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$CaptureParentSpanAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("subscribe"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("io.reactivex.MaybeObserver"))),
        getClass().getName() + "$PropagateParentSpanAdvice");
  }

  public static class CaptureParentSpanAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onConstruct(@Advice.This final Maybe<?> maybe) {
      AgentSpan parentSpan = activeSpan();
      if (parentSpan != null) {
        InstrumentationContext.get(Maybe.class, AgentSpan.class).put(maybe, parentSpan);
      }
    }
  }

  public static class PropagateParentSpanAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onSubscribe(
        @Advice.This final Maybe<?> maybe,
        @Advice.Argument(value = 0, readOnly = false) MaybeObserver<?> observer) {
      if (observer != null) {
        AgentSpan parentSpan = InstrumentationContext.get(Maybe.class, AgentSpan.class).get(maybe);
        if (parentSpan != null) {
          // wrap the observer so spans from its events treat the captured span as their parent
          observer = new TracingMaybeObserver<>(observer, parentSpan);
          // activate the span here in case additional observers are created during subscribe
          return activateSpan(parentSpan);
        }
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeScope(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
