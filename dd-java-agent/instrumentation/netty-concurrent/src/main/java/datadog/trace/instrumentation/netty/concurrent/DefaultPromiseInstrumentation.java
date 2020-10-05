package datadog.trace.instrumentation.netty.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import io.netty.util.concurrent.DefaultPromise;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class DefaultPromiseInstrumentation extends Instrumenter.Default {

  public DefaultPromiseInstrumentation() {
    super("netty-concurrent", "netty-promise-task");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "io.netty.util.concurrent.DefaultPromise", State.class.getName());
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return namedOneOf(
        "io.netty.util.concurrent.PromiseTask", "io.netty.util.concurrent.ScheduledFutureTask");
    // FIXME
    // "io.netty.util.concurrent.UnorderedThreadPoolEventExecutor$RunnableScheduledFutureTask");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    // target the constructor in PromiseTask
    transformers.put(
        isConstructor()
            .and(takesArguments(2))
            .and(takesArgument(0, named("io.netty.util.concurrent.EventExecutor")))
            .and(takesArgument(1, named(Callable.class.getName()))),
        getClass().getName() + "$Construct");
    transformers.put(isMethod().and(named("run")), getClass().getName() + "$Run");
    transformers.put(isMethod().and(named("cancel")), getClass().getName() + "$Cancel");
    transformers.put(isMethod().and(named("setFailureInternal")), getClass().getName() + "$Fail");
    return transformers;
  }

  public static final class Construct {
    @Advice.OnMethodExit
    public static <T> void construct(@Advice.This DefaultPromise<T> task) {
      TraceScope activeScope = activeScope();
      if (null != activeScope) {
        InstrumentationContext.get(DefaultPromise.class, State.class)
            .putIfAbsent(task, State.FACTORY)
            .captureAndSetContinuation(activeScope);
      }
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter
    public static <T> TraceScope before(@Advice.This DefaultPromise<T> task) {
      State state = InstrumentationContext.get(DefaultPromise.class, State.class).get(task);
      if (null != state) {
        TraceScope.Continuation continuation = state.getAndResetContinuation();
        if (null != continuation) {
          return continuation.activate();
        }
      }
      return null;
    }

    @Advice.OnMethodExit
    public static void after(@Advice.Enter TraceScope scope) {
      if (null != scope) {
        scope.close();
      }
    }
  }

  public static final class Cancel {
    @Advice.OnMethodExit
    public static <T> void cancel(@Advice.This DefaultPromise<T> task) {
      State state = InstrumentationContext.get(DefaultPromise.class, State.class).get(task);
      if (null != state) {
        state.closeContinuation();
      }
    }
  }

  public static final class Fail {
    @Advice.OnMethodEnter
    public static <T> void fail(@Advice.This DefaultPromise<T> task) {
      State state = InstrumentationContext.get(DefaultPromise.class, State.class).get(task);
      if (null != state) {
        state.closeContinuation();
      }
    }
  }
}
