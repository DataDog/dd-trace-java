package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExecutionContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

/** Instruments the Executor interface. Wraps Runnables, unless they are RunnableFutures. */
@AutoService(Instrumenter.class)
public class ExecutorInstrumentation extends FilteringExecutorInstrumentation {

  public ExecutorInstrumentation() {
    super("java_concurrent", "executor");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return ElementMatchers.<TypeDescription>not(nameEndsWith(".ForkJoinPool"))
        .and(super.typeMatcher());
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.util.concurrent.RunnableFuture", State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>(8);
    transformers.put(isMethod().and(named("execute")), getClass().getName() + "$Wrap");
    transformers.put(isMethod().and(named("shutdownNow")), getClass().getName() + "$ShutdownNow");
    // netty specific: considering pollTask equivalent to getTask
    transformers.put(
        isMethod().and(namedOneOf("getTask", "pollTask")), getClass().getName() + "$GetTask");
    transformers.put(isMethod().and(named("afterExecute")), getClass().getName() + "$AfterExecute");
    return unmodifiableMap(transformers);
  }

  public static final class Wrap {
    @Advice.OnMethodEnter
    public static void wrap(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
      if (null == runnable || (runnable instanceof RunnableFuture) || exclude(RUNNABLE, runnable)) {
        return;
      }
      TraceScope activeScope = activeScope();
      if (null != activeScope) {
        runnable = ExecutionContext.wrap(activeScope, runnable);
      }
    }
  }

  public static final class ShutdownNow {
    @Advice.OnMethodExit
    public static void shutdownNow(@Advice.Return(readOnly = false) List<Runnable> maybeWrapped) {
      if (!maybeWrapped.isEmpty()) {
        // safer to allocate than modify in place, could be an immutable/unmodifiable list
        List<Runnable> unwrapped = new ArrayList<>(maybeWrapped.size());
        for (Runnable runnable : maybeWrapped) {
          if (runnable instanceof ExecutionContext) {
            unwrapped.add(((ExecutionContext) runnable).activateAndUnwrap());
          } else {
            unwrapped.add(runnable);
          }
        }
        maybeWrapped = unwrapped;
      }
    }
  }

  public static final class GetTask {
    @Advice.OnMethodExit
    public static void getTask(@Advice.Return(readOnly = false) Runnable task) {
      if (task instanceof ExecutionContext) {
        task = ((ExecutionContext) task).activateAndUnwrap();
      }
    }
  }

  public static final class AfterExecute {
    @Advice.OnMethodEnter
    public static void afterExecute(@Advice.Argument(0) Runnable executed) {
      if (!(executed instanceof RunnableFuture)) {
        ExecutionContext.clear(executed);
      }
    }
  }
}
