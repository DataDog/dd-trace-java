package datadog.trace.instrumentation.java.concurrent.executor;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE_FUTURE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.QueueTimerHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TPEHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import java.util.Queue;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Tomcat 9.0.52 / 10.0.10 forked the JDK executor instead of extending it. */
public final class TomcatThreadPoolExecutorInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  @Override
  public String hierarchyMarkerType() {
    return "org.apache.tomcat.util.threads.ThreadPoolExecutor";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()))
        .and(not(extendsClass(named("java.util.concurrent.ThreadPoolExecutor"))));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    ElementMatcher.Junction<MethodDescription> timedExecute =
        named("execute").and(takesArguments(Runnable.class, long.class, TimeUnit.class));
    // Older forks delegate the one-argument method to the timed overload.
    ElementMatcher.Junction<MethodDescription> execute =
        named("execute")
            .and(takesArguments(Runnable.class))
            .and(isDeclaredBy(not(declaresMethod(timedExecute))));
    // Only the fork itself can access its private workQueue field. Overrides delegate here.
    transformer.applyAdvice(
        isMethod().and(isDeclaredBy(named(hierarchyMarkerType()))).and(timedExecute.or(execute)),
        getClass().getName() + "$ExecuteAdvice");
    transformer.applyAdvice(
        isMethod().and(named("beforeExecute")).and(takesArguments(Thread.class, Runnable.class)),
        ThreadPoolExecutorInstrumentation.class.getName() + "$BeforeExecute");
    transformer.applyAdvice(
        isMethod().and(named("afterExecute")).and(takesArguments(Runnable.class, Throwable.class)),
        ThreadPoolExecutorInstrumentation.class.getName() + "$AfterExecute");
    transformer.applyAdvice(
        isMethod()
            .and(isDeclaredBy(named(hierarchyMarkerType())))
            .and(named("remove"))
            .and(takesArguments(Runnable.class))
            .and(returns(boolean.class)),
        getClass().getName() + "$RemoveAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isDeclaredBy(named(hierarchyMarkerType())))
            .and(named("reject"))
            .and(takesArguments(Runnable.class)),
        getClass().getName() + "$RejectAdvice");
  }

  public static final class ExecuteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.This Object executor,
        @Advice.FieldValue("workQueue") Queue<?> queue,
        @Advice.Argument(value = 0, readOnly = false) Runnable task) {
      if (TPEHelper.shouldPropagate(executor)) {
        if (TPEHelper.useWrapping(task)) {
          task = Wrapper.wrap(task);
        } else if (task != null) {
          TPEHelper.capture(InstrumentationContext.get(Runnable.class, State.class), task);
          if (QueueTimerHelper.isReady() && !exclude(EXECUTOR, executor)) {
            if (!exclude(RUNNABLE, task)) {
              QueueTimerHelper.startQueuingTimer(
                  InstrumentationContext.get(Runnable.class, State.class),
                  executor.getClass(),
                  queue.getClass(),
                  queue.size(),
                  task);
            } else if (task instanceof RunnableFuture && !exclude(RUNNABLE_FUTURE, task)) {
              QueueTimerHelper.startQueuingTimer(
                  InstrumentationContext.get(RunnableFuture.class, State.class),
                  executor.getClass(),
                  queue.getClass(),
                  queue.size(),
                  (RunnableFuture<?>) task);
            }
          }
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.This Object executor,
        @Advice.Argument(0) Runnable task,
        @Advice.Thrown Throwable error) {
      if (error != null && TPEHelper.shouldPropagate(executor)) {
        // Tomcat may recover from an internal rejection by forcing the task onto its queue.
        TPEHelper.cancelTask(
            InstrumentationContext.get(Runnable.class, State.class),
            InstrumentationContext.get(RunnableFuture.class, State.class),
            task);
      }
    }
  }

  public static final class RemoveAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.This Object executor,
        @Advice.Argument(0) Runnable task,
        @Advice.Return boolean removed) {
      if (removed && TPEHelper.shouldPropagate(executor)) {
        TPEHelper.cancelTask(
            InstrumentationContext.get(Runnable.class, State.class),
            InstrumentationContext.get(RunnableFuture.class, State.class),
            task);
      }
    }
  }

  public static final class RejectAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Wrapper<?> enter(
        @Advice.This Object executor, @Advice.Argument(value = 0, readOnly = false) Runnable task) {
      if (TPEHelper.shouldPropagate(executor) && task instanceof Wrapper) {
        Wrapper<?> wrapper = (Wrapper<?>) task;
        task = wrapper.unwrap();
        return wrapper;
      }
      return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.This Object executor,
        @Advice.FieldValue("workQueue") Queue<?> queue,
        @Advice.Enter Wrapper<?> wrapper,
        @Advice.Argument(0) Runnable task) {
      // A throwing handler can still be followed by a successful queue.force retry. Only release
      // here when the handler returns normally and has not re-enqueued the task.
      if (TPEHelper.shouldPropagate(executor)) {
        if (wrapper != null) {
          wrapper.cancel();
        } else if (!queue.contains(task)) {
          TPEHelper.cancelTask(
              InstrumentationContext.get(Runnable.class, State.class),
              InstrumentationContext.get(RunnableFuture.class, State.class),
              task);
        }
      }
    }
  }
}
