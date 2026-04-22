package datadog.trace.instrumentation.java.concurrent.executor;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE_FUTURE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.QueueTimerHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TPEHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import java.util.Queue;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The old way of doing this is to wrap the Runnable when it is added to the queue, which is scary
 * by itself, since the queue can contain any type of object that implements Runnable, and the queue
 * implementation can try to cast it to something that our wrapper doesn't implement. To avoid this
 * we use the existing State field context store in the Runnable and hand off the AgentScope from
 * beforeExecute to afterExecute via a ThreadLocal.
 *
 * <p>Here is a simple flow chart for the non wrapping version with + signifying added code:
 *
 * <pre>{@code
 * beforeExecute -> enter
 *                  + start AgentScope if available and pass it to exit
 *                  normal method body
 *                  exit
 *               <- + store AgentScope in ThreadLocal if available
 * normal execution of the Runnable
 * afterExecute  -> enter
 *                  + clear and pass ThreadLocal AgentScope if available to exit
 *                  normal method body
 *                  exit
 *               <- + close AgentScope if available
 * }</pre>
 */
public final class ThreadPoolExecutorInstrumentation
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForTypeHierarchy,
        Instrumenter.HasMethodAdvice {
  static final String TPE = "java.util.concurrent.ThreadPoolExecutor";

  // executors which do their own wrapping before calling super,
  // leading to double wrapping, once at the child level and once
  // in ThreadPoolExecutor
  private static final ElementMatcher<MethodDescription> NO_WRAPPING_BEFORE_DELEGATION =
      not(
          isDeclaredBy(
              namedOneOf("org.elasticsearch.common.util.concurrent.EsThreadPoolExecutor")));

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return not(named("java.util.concurrent.ScheduledThreadPoolExecutor"))
        .and(extendsClass(named(TPE)));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Init");
    transformer.applyAdvice(
        named("execute")
            .and(isMethod())
            .and(NO_WRAPPING_BEFORE_DELEGATION)
            .and(takesArgument(0, named(Runnable.class.getName()))),
        getClass().getName() + "$Execute");
    transformer.applyAdvice(
        named("beforeExecute")
            .and(isMethod())
            .and(takesArgument(1, named(Runnable.class.getName()))),
        getClass().getName() + "$BeforeExecute");
    transformer.applyAdvice(
        named("afterExecute")
            .and(isMethod())
            .and(takesArgument(0, named(Runnable.class.getName()))),
        getClass().getName() + "$AfterExecute");
    transformer.applyAdvice(
        named("remove").and(isMethod()).and(returns(Runnable.class)),
        getClass().getName() + "$Remove");
  }

  public static final class Init {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void decideWrapping(@Advice.This final ThreadPoolExecutor zis) {
      // avoid tracking threads when building native images as it confuses the scanner
      // (we still want instrumentation applied, so tracking works in the built image)
      if (!Platform.isNativeImageBuilder()) {
        TPEHelper.setPropagate(
            InstrumentationContext.get(ThreadPoolExecutor.class, Boolean.class), zis);
      }
    }
  }

  public static final class Execute {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void capture(
        @Advice.This final ThreadPoolExecutor tpe,
        @Advice.Argument(readOnly = false, value = 0) Runnable task) {
      if (TPEHelper.shouldPropagate(
          InstrumentationContext.get(ThreadPoolExecutor.class, Boolean.class), tpe)) {
        if (TPEHelper.useWrapping(task)) {
          task = Wrapper.wrap(task);
        } else {
          TPEHelper.capture(InstrumentationContext.get(Runnable.class, State.class), task);
          // queue time needs to be handled separately because there are RunnableFutures which are
          // excluded as
          // Runnables but it is not until now that they will be put on the executor's queue
          if (!exclude(EXECUTOR, tpe)) {
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
    }
  }

  public static final class BeforeExecute {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beforeExecuteEnter(
        @Advice.This final ThreadPoolExecutor zis,
        @Advice.Argument(readOnly = false, value = 1) Runnable task) {
      if (TPEHelper.shouldPropagate(
          InstrumentationContext.get(ThreadPoolExecutor.class, Boolean.class), zis)) {
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
        @Advice.Enter final AgentScope scope, @Advice.Argument(value = 1) Runnable task) {
      if (scope != null) {
        TPEHelper.setThreadLocalScope(scope, task);
      }
    }
  }

  public static final class AfterExecute {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope afterExecuteEnter(
        @Advice.This final ThreadPoolExecutor zis,
        @Advice.Argument(readOnly = false, value = 0) Runnable task) {
      if (TPEHelper.shouldPropagate(
          InstrumentationContext.get(ThreadPoolExecutor.class, Boolean.class), zis)) {
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
        @Advice.Enter final AgentScope scope, @Advice.Argument(value = 0) Runnable task) {
      if (scope != null) {
        TPEHelper.endScope(scope, task);
      }
    }
  }

  public static final class Remove {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void remove(
        @Advice.This final ThreadPoolExecutor zis,
        @Advice.Return(readOnly = false) Runnable removed) {
      if (TPEHelper.shouldPropagate(
          InstrumentationContext.get(ThreadPoolExecutor.class, Boolean.class), zis)) {
        if (TPEHelper.useWrapping(removed)) {
          if (removed instanceof Wrapper) {
            Wrapper<?> wrapper = ((Wrapper<?>) removed);
            wrapper.cancel();
            removed = wrapper.unwrap();
          }
        } else {
          TPEHelper.cancelTask(InstrumentationContext.get(Runnable.class, State.class), removed);
        }
      }
    }
  }
}
