package datadog.trace.instrumentation.tomcat9.executor;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE_FUTURE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.QueueTimerHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TPEHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import java.util.Queue;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;

/** Instruments Tomcat's executor after it stopped extending the JDK ThreadPoolExecutor. */
public final class TomcatThreadPoolExecutorInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static final String TOMCAT_EXECUTOR = "org.apache.tomcat.util.threads.ThreadPoolExecutor";

  @Override
  public String hierarchyMarkerType() {
    return TOMCAT_EXECUTOR;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(TOMCAT_EXECUTOR))
        .and(not(extendsClass(named("java.util.concurrent.ThreadPoolExecutor"))));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("execute")
            .and(isMethod())
            .and(returns(void.class))
            .and(takesArgument(0, Runnable.class))
            .and(
                takesArguments(1)
                    .or(
                        takesArguments(3)
                            .and(takesArgument(1, long.class))
                            .and(takesArgument(2, TimeUnit.class)))),
        getClass().getName() + "$Execute");
    transformer.applyAdvice(
        named("beforeExecute").and(isMethod()).and(takesArgument(1, Runnable.class)),
        getClass().getName() + "$BeforeExecute");
    transformer.applyAdvice(
        named("afterExecute").and(isMethod()).and(takesArgument(0, Runnable.class)),
        getClass().getName() + "$AfterExecute");
    transformer.applyAdvice(
        named("remove")
            .and(isMethod())
            .and(takesArguments(1))
            .and(takesArgument(0, Runnable.class))
            .and(returns(boolean.class)),
        getClass().getName() + "$Remove");
  }

  public static final class Execute {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean capture(
        @Advice.This ThreadPoolExecutor tpe,
        @Advice.Argument(value = 0, readOnly = false) Runnable task) {
      // Tomcat 9's execute(Runnable) delegates to execute(Runnable, long, TimeUnit).
      // Capture context and start queue timing only when the previous depth is 0, for either
      // overload.
      if (CallDepthThreadLocalMap.incrementCallDepth(ThreadPoolExecutor.class) != 0) {
        return false;
      }
      if (TPEHelper.shouldPropagate(tpe)) {
        if (TPEHelper.useWrapping(task)) {
          task = Wrapper.wrap(task);
        } else {
          TPEHelper.capture(InstrumentationContext.get(Runnable.class, State.class), task);
          if (QueueTimerHelper.isReady() && !exclude(EXECUTOR, tpe)) {
            if (!exclude(RUNNABLE, task)) {
              Queue<?> queue = tpe.getQueue();
              QueueTimerHelper.startQueuingTimer(
                  InstrumentationContext.get(Runnable.class, State.class),
                  tpe.getClass(),
                  queue.getClass(),
                  queue.size(),
                  task);
            } else if (!exclude(RUNNABLE_FUTURE, task) && task instanceof RunnableFuture) {
              Queue<?> queue = tpe.getQueue();
              QueueTimerHelper.startQueuingTimer(
                  InstrumentationContext.get(RunnableFuture.class, State.class),
                  tpe.getClass(),
                  queue.getClass(),
                  queue.size(),
                  (RunnableFuture<?>) task);
            }
          }
        }
      }
      return true;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void finish(
        @Advice.Enter boolean outermost,
        @Advice.This ThreadPoolExecutor tpe,
        @Advice.Argument(0) Runnable task,
        @Advice.Thrown Throwable error) {
      // Balance every increment, including nested overload calls and exceptional exits.
      CallDepthThreadLocalMap.decrementCallDepth(ThreadPoolExecutor.class);
      if (outermost && error != null && TPEHelper.shouldPropagate(tpe)) {
        if (task instanceof Wrapper) {
          ((Wrapper<?>) task).cancel();
        } else {
          TPEHelper.cancelTask(InstrumentationContext.get(Runnable.class, State.class), task);
          if (task instanceof RunnableFuture) {
            cancelTask(
                InstrumentationContext.get(RunnableFuture.class, State.class),
                (RunnableFuture<?>) task);
          }
        }
      }
    }
  }

  public static final class BeforeExecute {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope beforeExecuteEnter(
        @Advice.This ThreadPoolExecutor tpe,
        @Advice.Argument(value = 1, readOnly = false) Runnable task) {
      if (TPEHelper.shouldPropagate(tpe)) {
        if (TPEHelper.useWrapping(task)) {
          task = Wrapper.unwrap(task);
        } else {
          return TPEHelper.startScope(
              InstrumentationContext.get(Runnable.class, State.class), task);
        }
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void beforeExecuteExit(
        @Advice.Enter ContextScope scope, @Advice.Argument(1) Runnable task) {
      if (scope != null) {
        TPEHelper.setThreadLocalScope(scope, task);
      }
    }
  }

  public static final class AfterExecute {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope afterExecuteEnter(
        @Advice.This ThreadPoolExecutor tpe,
        @Advice.Argument(value = 0, readOnly = false) Runnable task) {
      if (TPEHelper.shouldPropagate(tpe)) {
        if (TPEHelper.useWrapping(task)) {
          task = Wrapper.unwrap(task);
        } else {
          return TPEHelper.getAndClearThreadLocalScope(task);
        }
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterExecuteExit(
        @Advice.Enter ContextScope scope, @Advice.Argument(0) Runnable task) {
      if (scope != null) {
        TPEHelper.endScope(scope, task);
      }
    }
  }

  public static final class Remove {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void remove(
        @Advice.This ThreadPoolExecutor tpe,
        @Advice.Argument(0) Runnable task,
        @Advice.Return boolean removed) {
      if (removed && TPEHelper.shouldPropagate(tpe)) {
        if (task instanceof Wrapper) {
          ((Wrapper<?>) task).cancel();
        } else {
          TPEHelper.cancelTask(InstrumentationContext.get(Runnable.class, State.class), task);
        }
      }
    }
  }
}
