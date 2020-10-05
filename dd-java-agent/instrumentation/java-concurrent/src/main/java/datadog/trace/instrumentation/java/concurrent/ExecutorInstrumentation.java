package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedNoneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExecutionContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

@AutoService(Instrumenter.class)
public final class ExecutorInstrumentation extends Instrumenter.Default {

  // reviewers - check a test accompanies inclusion in this list
  private static final String[] TESTED = {
    // TODO must test all of these in akka-concurrent
    "akka.actor.ActorSystemImpl$$anon$1",
    "akka.dispatch.BalancingDispatcher",
    "akka.dispatch.Dispatcher",
    "akka.dispatch.Dispatcher$LazyExecutorServiceDelegate",
    "akka.dispatch.ExecutionContexts$sameThreadExecutionContext$",
    "akka.dispatch.MessageDispatcher",
    "akka.dispatch.PinnedDispatcher",

    // below are all really tested
    "java.util.concurrent.AbstractExecutorService",
    "java.util.concurrent.ThreadPoolExecutor",
    "java.util.concurrent.Executors$DelegatedExecutorService",
    "java.util.concurrent.Executors$FinalizableDelegatedExecutorService",
    "java.util.concurrent.CompletableFuture$ThreadPerTaskExecutor",
    "org.eclipse.jetty.util.thread.QueuedThreadPool",
    "org.eclipse.jetty.util.thread.ReservedThreadExecutor",
    "java.util.concurrent.ForkJoinPool",
    // tested by scala-concurrent
    "scala.concurrent.forkjoin.ForkJoinPool",
    // tested by akka-concurrent
    "akka.dispatch.forkjoin.ForkJoinPool"
  };

  private static final String[] MUST_SKIP = {
    // ScheduledThreadPoolExecutor.execute is fully supported by instrumenting
    // FutureTask, and wrapping calls to execute interferes with this mechanism
    "java.util.concurrent.ScheduledThreadPoolExecutor"
  };

  public ExecutorInstrumentation() {
    super("java-concurrent", "executor");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return ElementMatchers.<TypeDescription>namedOneOf(TESTED)
        .or(
            namedNoneOf(MUST_SKIP)
                .and(not(nameContains(".netty.util.concurrent.")))
                .and(hasInterface(named("java.util.concurrent.Executor"))));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.util.concurrent.FutureTask", State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>(8);
    transformers.put(
        isMethod().and(named("execute")).and(takesArgument(0, named(Runnable.class.getName()))),
        getClass().getName() + "$Wrap");
    transformers.put(
        isMethod().and(named("reject").and(takesArgument(0, named(Runnable.class.getName())))),
        getClass().getName() + "$Reject");
    transformers.put(isMethod().and(named("shutdownNow")), getClass().getName() + "$ShutdownNow");
    transformers.put(
        isMethod().and(named("getTask")).and(isPrivate()).and(takesNoArguments()),
        getClass().getName() + "$GetTask");
    transformers.put(isMethod().and(named("afterExecute")), getClass().getName() + "$AfterExecute");
    return transformers;
  }

  public static final class Wrap {
    @Advice.OnMethodEnter
    public static void wrap(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
      // if a user has called execute directly we'll need to wrap the
      // task to avoid needing to instrument all Runnables, but need
      // to be careful that executor code paths handled by FutureTaskInstrumentation
      // will call execute - so don't interfere with FutureTasks
      //
      // let the executor produce NPE itself to give a better line number
      if (null != runnable && !(runnable instanceof FutureTask)) {
        TraceScope scope = activeScope();
        if (null != scope) {
          runnable = ExecutionContext.wrap(scope, runnable);
        }
      }
    }
  }

  public static final class Reject {
    @Advice.OnMethodEnter
    public static void reject(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
      if (runnable instanceof ExecutionContext) {
        ((ExecutionContext) runnable).cancel();
      }
      if (runnable instanceof FutureTask) {
        State state =
            InstrumentationContext.get(FutureTask.class, State.class).get((FutureTask) runnable);
        if (null != state) {
          state.closeContinuation();
        }
      }
    }
  }

  public static final class ShutdownNow {
    @Advice.OnMethodExit
    public static List<Runnable> shutdownNow(@Advice.Return List<Runnable> maybeWrapped) {
      // safer to allocate than modify in place, could be an immutable/unmodifiable list
      List<Runnable> unwrapped = new ArrayList<>(maybeWrapped.size());
      for (Runnable runnable : maybeWrapped) {
        if (runnable instanceof ExecutionContext) {
          unwrapped.add(((ExecutionContext) runnable).activateAndUnwrap());
        } else {
          unwrapped.add(runnable);
        }
      }
      return unwrapped;
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
      if (!(executed instanceof FutureTask)) {
        ExecutionContext.clearExecutionContext(executed);
      }
    }
  }
}
