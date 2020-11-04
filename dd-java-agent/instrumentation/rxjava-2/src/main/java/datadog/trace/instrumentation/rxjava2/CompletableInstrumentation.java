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
import io.reactivex.Completable;
import io.reactivex.CompletableObserver;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class CompletableInstrumentation extends Instrumenter.Default {
  public CompletableInstrumentation() {
    super("rxjava");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("io.reactivex.Completable");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TracingCompletableObserver",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("io.reactivex.Completable", AgentSpan.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        isConstructor(), packageName + ".CompletableInstrumentation$CompletableAdvice");
    transformers.put(
        isMethod()
            .and(named("subscribe"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("io.reactivex.CompletableObserver"))),
        packageName + ".CompletableInstrumentation$SubscribeAdvice");
    return transformers;
  }

  public static class CompletableAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onConstruct(@Advice.This final Completable thiz) {
      AgentSpan span = AgentTracer.activeSpan();
      if (span == null) {
        span = AgentTracer.noopSpan();
      }
      InstrumentationContext.get(Completable.class, AgentSpan.class).put(thiz, span);
    }
  }

  public static class SubscribeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope openScope(
        @Advice.This final Completable thiz,
        @Advice.Argument(value = 0, readOnly = false) CompletableObserver observer) {
      if (observer != null) {
        AgentSpan span = InstrumentationContext.get(Completable.class, AgentSpan.class).get(thiz);
        if (span != null) {
          observer = new TracingCompletableObserver(observer, span);
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
