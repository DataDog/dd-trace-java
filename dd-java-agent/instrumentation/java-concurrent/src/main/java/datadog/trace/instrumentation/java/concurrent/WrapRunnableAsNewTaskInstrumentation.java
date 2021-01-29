package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ComparableRunnable;
import datadog.trace.bootstrap.instrumentation.java.concurrent.NewTaskForPlaceholder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatcher.Junction;

@AutoService(Instrumenter.class)
public final class WrapRunnableAsNewTaskInstrumentation extends Instrumenter.Tracing {
  public WrapRunnableAsNewTaskInstrumentation() {
    super("java_concurrent", "new-task-for");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return namedOneOf(
        "io.netty.channel.epoll.EpollEventLoop",
        "io.netty.channel.nio.NioEventLoop",
        "io.netty.channel.SingleThreadEventLoop",
        "io.netty.util.concurrent.AbstractEventExecutor",
        "io.netty.util.concurrent.AbstractScheduledEventExecutor",
        "io.netty.util.concurrent.DefaultEventExecutor",
        "io.netty.util.concurrent.GlobalEventExecutor",
        "io.netty.util.concurrent.SingleThreadEventExecutor",
        "java.util.concurrent.AbstractExecutorService",
        "java.util.concurrent.CompletableFuture$ThreadPerTaskExecutor",
        "java.util.concurrent.SubmissionPublisher$ThreadPerTaskExecutor",
        "org.glassfish.grizzly.threadpool.GrizzlyExecutorService");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<MethodDescription>, String> transformers = new HashMap<>();

    Junction<MethodDescription> hasExecute =
        isMethod().and(named("execute").and(takesArgument(0, named(Runnable.class.getName()))));

    Junction<MethodDescription> hasNewTaskFor =
        isDeclaredBy(extendsClass(named("java.util.concurrent.AbstractExecutorService")));

    // executors that extend AbstractExecutorService should use 'newTaskFor' wrapper
    transformers.put(hasExecute.and(hasNewTaskFor), getClass().getName() + "$NewTaskFor");

    // use simple wrapper for executors that don't extend AbstractExecutorService
    transformers.put(hasExecute.and(not(hasNewTaskFor)), getClass().getName() + "$Wrap");

    return transformers;
  }

  // We tolerate a bit of duplication between these advice classes because
  // it avoids having to inject other helper classes onto the bootclasspath

  /**
   * Wrapper that uses {@link AbstractExecutorService#newTaskFor}.
   *
   * <p>The placeholder 'newTaskFor' is rewritten at build time to call the real method (which has
   * protected access so javac won't let us call it here directly). Once rewritten this advice can
   * only be applied to types that extend {@link AbstractExecutorService} otherwise we'll get class
   * verification errors about this call in the transformed executor.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static final class NewTaskFor {
    @Advice.OnMethodEnter
    public static void execute(
        @Advice.This Executor executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task) {
      if (task instanceof RunnableFuture || null == task || exclude(RUNNABLE, task)) {
        // no wrapping required
      } else if (task instanceof Comparable) {
        task = new ComparableRunnable(task);
      } else {
        task = NewTaskForPlaceholder.newTaskFor(executor, task, null);
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void cancel(@Advice.Argument(0) Runnable task, @Advice.Thrown Throwable error) {
      if (null != error && task instanceof RunnableFuture) {
        ((RunnableFuture) task).cancel(true);
      }
    }
  }

  /** More general wrapper that uses {@link FutureTask} instead of calling 'newTaskFor'. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static final class Wrap {
    @Advice.OnMethodEnter
    public static void execute(
        @Advice.This Executor executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task) {
      if (task instanceof RunnableFuture || null == task || exclude(RUNNABLE, task)) {
        // no wrapping required
      } else if (task instanceof Comparable) {
        task = new ComparableRunnable(task);
      } else {
        task = new FutureTask<>(task, null);
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void cancel(@Advice.Argument(0) Runnable task, @Advice.Thrown Throwable error) {
      if (null != error && task instanceof RunnableFuture) {
        ((RunnableFuture) task).cancel(true);
      }
    }
  }
}
