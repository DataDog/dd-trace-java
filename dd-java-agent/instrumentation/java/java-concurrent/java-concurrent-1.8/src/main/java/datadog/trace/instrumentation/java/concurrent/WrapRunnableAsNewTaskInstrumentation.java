package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.NewTaskForPlaceholder;
import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher.Junction;

@AutoService(InstrumenterModule.class)
public final class WrapRunnableAsNewTaskInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForBootstrap, Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  public WrapRunnableAsNewTaskInstrumentation() {
    super(EXECUTOR_INSTRUMENTATION_NAME, "new-task-for");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.netty.channel.epoll.EpollEventLoop",
      "io.netty.channel.nio.NioEventLoop",
      "io.netty.channel.SingleThreadEventLoop",
      "io.netty.util.concurrent.AbstractEventExecutor",
      "io.netty.util.concurrent.AbstractScheduledEventExecutor",
      "io.netty.util.concurrent.DefaultEventExecutor",
      "io.netty.util.concurrent.GlobalEventExecutor",
      "io.netty.util.concurrent.SingleThreadEventExecutor",
      "java.util.concurrent.AbstractExecutorService",
      "org.glassfish.grizzly.threadpool.GrizzlyExecutorService",
      "org.jboss.threads.EnhancedQueueExecutor",
      "io.vertx.core.impl.WorkerExecutor",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    Junction<MethodDescription> hasExecute =
        isMethod().and(named("execute").and(takesArgument(0, named(Runnable.class.getName()))));

    Junction<MethodDescription> hasNewTaskFor =
        isDeclaredBy(extendsClass(named("java.util.concurrent.AbstractExecutorService")));

    // executors that extend AbstractExecutorService should use 'newTaskFor' wrapper
    transformer.applyAdvice(hasExecute.and(hasNewTaskFor), getClass().getName() + "$NewTaskFor");

    // use simple wrapper for executors that don't extend AbstractExecutorService
    transformer.applyAdvice(hasExecute.and(not(hasNewTaskFor)), getClass().getName() + "$Wrap");
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
  @SuppressWarnings("rawtypes")
  public static final class NewTaskFor {
    @Advice.OnMethodEnter
    public static boolean execute(
        @Advice.This Executor executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task) {
      if (task instanceof RunnableFuture || null == task || exclude(RUNNABLE, task)) {
        return false;
        // no wrapping required
      } else if (task instanceof Comparable) {
        task = Wrapper.wrap(task);
      } else {
        task = NewTaskForPlaceholder.newTaskFor(executor, task, null);
      }
      return true;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void cancel(
        @Advice.Enter boolean wrapped,
        @Advice.Argument(0) Runnable task,
        @Advice.Thrown Throwable error) {
      // don't cancel unless we did the wrapping
      if (wrapped && null != error && task instanceof RunnableFuture) {
        ((RunnableFuture) task).cancel(true);
      }
    }
  }

  /** More general wrapper that uses {@link Wrapper} instead of calling 'newTaskFor'. */
  @SuppressWarnings("rawtypes")
  public static final class Wrap {
    @Advice.OnMethodEnter
    public static void execute(@Advice.Argument(value = 0, readOnly = false) Runnable task) {
      task = Wrapper.wrap(task);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void cancel(@Advice.Argument(0) Runnable task, @Advice.Thrown Throwable error) {
      if (null != error && task instanceof Wrapper) {
        ((Wrapper) task).cancel();
      }
    }
  }
}
