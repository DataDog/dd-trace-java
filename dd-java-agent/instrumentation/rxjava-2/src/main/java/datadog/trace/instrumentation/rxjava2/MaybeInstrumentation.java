package datadog.trace.instrumentation.rxjava2;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.reactivex.Maybe;
import io.reactivex.MaybeObserver;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class MaybeInstrumentation extends Instrumenter.Default {
  public MaybeInstrumentation() {
    super("rxjava");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("io.reactivex.Maybe");
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
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(isConstructor(), packageName + ".MaybeInstrumentation$MaybeAdvice");
    transformers.put(
        isMethod()
            .and(named("subscribe"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("io.reactivex.MaybeObserver"))),
        packageName + ".MaybeInstrumentation$SubscribeAdvice");
    return transformers;
  }

  public static class MaybeAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onConstruct(@Advice.This final Maybe<?> thiz) {
      AgentSpan span = AgentTracer.activeSpan();
      if (span == null) {
        span = AgentTracer.noopSpan();
      }
      InstrumentationContext.get(Maybe.class, AgentSpan.class).put(thiz, span);
    }
  }

  public static class SubscribeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope openScope(
        @Advice.This final Maybe<?> thiz,
        @Advice.Argument(value = 0, readOnly = false) MaybeObserver<?> observer) {
      if (observer != null) {
        AgentSpan span = InstrumentationContext.get(Maybe.class, AgentSpan.class).get(thiz);
        if (span != null) {
          observer = new TracingMaybeObserver<>(observer, span);
          return AgentTracer.activateSpan(span);
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
