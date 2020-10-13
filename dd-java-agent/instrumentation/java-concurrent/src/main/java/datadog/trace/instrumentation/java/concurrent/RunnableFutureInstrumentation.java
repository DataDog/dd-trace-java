package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.hasInterface;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class RunnableFutureInstrumentation extends Instrumenter.Default {
  public RunnableFutureInstrumentation() {
    super("java_concurrent", "runnable-future");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return hasInterface(named("java.util.concurrent.RunnableFuture"));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.util.concurrent.RunnableFuture", State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<MethodDescription>, String> transformers = new HashMap<>(8);
    // TODO should target some particular implementations to prevent this from happening all
    //  the way up the constructor chain (even though the advice applied is cheap)
    transformers.put(isConstructor(), getClass().getName() + "$Construct");
    transformers.put(isMethod().and(named("run")), getClass().getName() + "$Run");
    transformers.put(isMethod().and(named("cancel")), getClass().getName() + "$Cancel");
    return unmodifiableMap(transformers);
  }

  public static final class Construct {

    @Advice.OnMethodExit
    public static <T> void captureScope(@Advice.This RunnableFuture<T> task) {
      TraceScope activeScope = activeScope();
      if (null != activeScope) {
        InstrumentationContext.get(RunnableFuture.class, State.class)
            .putIfAbsent(task, State.FACTORY)
            .captureAndSetContinuation(activeScope);
      }
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter
    public static <T> TraceScope activate(@Advice.This RunnableFuture<T> task) {
      return startTaskScope(InstrumentationContext.get(RunnableFuture.class, State.class), task);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void close(@Advice.Enter TraceScope scope) {
      endTaskScope(scope);
    }
  }

  public static final class Cancel {
    @Advice.OnMethodEnter
    public static <T> void cancel(@Advice.This RunnableFuture<T> task) {
      cancelTask(InstrumentationContext.get(RunnableFuture.class, State.class), task);
    }
  }
}
