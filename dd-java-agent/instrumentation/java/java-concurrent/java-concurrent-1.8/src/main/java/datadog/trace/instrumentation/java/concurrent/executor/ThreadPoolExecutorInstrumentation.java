package datadog.trace.instrumentation.java.concurrent.executor;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.currentContext;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE_FUTURE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.QueueTimerHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TPEHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import java.util.List;
import java.util.ListIterator;
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
 * we use the existing State field context store in the Runnable and hand off the ContextScope from
 * beforeExecute to afterExecute via a ThreadLocal.
 *
 * <p>Here is a simple flow chart for the non wrapping version with + signifying added code:
 *
 * <pre>{@code
 * beforeExecute -> enter
 *                  + start ContextScope if available and pass it to exit
 *                  normal method body
 *                  exit
 *               <- + store ContextScope in ThreadLocal if available
 * normal execution of the Runnable
 * afterExecute  -> enter
 *                  + clear and pass ThreadLocal ContextScope if available to exit
 *                  normal method body
 *                  exit
 *               <- + close ContextScope if available
 * }</pre>
 */
public final class ThreadPoolExecutorInstrumentation
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForTypeHierarchy,
        Instrumenter.HasMethodAdvice {

  // This executor decorates tasks before delegating, so its override must not use the legacy
  // compatibility path. Exact ownership is captured later by ThreadPoolExecutor.execute.
  private static final ElementMatcher<MethodDescription> DECORATES_BEFORE_DELEGATION =
      isDeclaredBy(namedOneOf("org.elasticsearch.common.util.concurrent.EsThreadPoolExecutor"));

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return not(named("java.util.concurrent.ScheduledThreadPoolExecutor"))
        .and(extendsClass(named("java.util.concurrent.ThreadPoolExecutor")));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("execute")
            .and(isMethod())
            .and(isDeclaredBy(named(ThreadPoolExecutor.class.getName())))
            .and(takesArgument(0, named(Runnable.class.getName()))),
        getClass().getName() + "$Execute");
    transformer.applyAdvice(
        // Preserve behavior for overrides that may bypass JDK admission. This path is
        // intentionally best-effort; exact submission ownership begins in ThreadPoolExecutor.
        named("execute")
            .and(isMethod())
            .and(not(isDeclaredBy(named(ThreadPoolExecutor.class.getName()))))
            .and(not(DECORATES_BEFORE_DELEGATION))
            .and(takesArgument(0, named(Runnable.class.getName())))
            .and(takesArguments(1)),
        getClass().getName() + "$ExecuteOverride");
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
        named("remove")
            .and(isMethod())
            .and(isDeclaredBy(named(ThreadPoolExecutor.class.getName())))
            .and(takesArgument(0, named(Runnable.class.getName())))
            .and(returns(boolean.class)),
        getClass().getName() + "$Remove");
    transformer.applyAdvice(
        named("shutdownNow")
            .and(isMethod())
            .and(isDeclaredBy(named(ThreadPoolExecutor.class.getName())))
            .and(takesArguments(0))
            .and(returns(List.class)),
        getClass().getName() + "$ShutdownNow");
  }

  public static final class Execute {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void capture(
        @Advice.This final ThreadPoolExecutor tpe,
        @Advice.Argument(readOnly = false, value = 0) Runnable task) {
      if (TPEHelper.shouldPropagate(tpe)) {
        if (TPEHelper.useWrapping(task)) {
          task = Wrapper.wrap(task);
        } else {
          Runnable captured =
              TPEHelper.captureOrWrap(
                  InstrumentationContext.get(Runnable.class, State.class),
                  task,
                  currentContext(),
                  tpe);
          if (captured == null) {
            return;
          }
          task = captured;
          // queue time needs to be handled separately because there are RunnableFutures which
          // are excluded as Runnables but it is not until now that they will be put on the
          // executor's queue
          if (!exclude(EXECUTOR, tpe)) {
            if (!(task instanceof Wrapper) && !exclude(RUNNABLE, task)) {
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

  public static final class ExecuteOverride {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void capture(
        @Advice.This final ThreadPoolExecutor tpe,
        @Advice.Argument(readOnly = false, value = 0) Runnable task) {
      if (TPEHelper.shouldPropagate(tpe)) {
        if (TPEHelper.useWrapping(task)) {
          task = Wrapper.wrap(task);
        } else {
          TPEHelper.captureLegacy(InstrumentationContext.get(Runnable.class, State.class), task);
        }
      }
    }
  }

  public static final class BeforeExecute {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope beforeExecuteEnter(
        @Advice.This final ThreadPoolExecutor tpe,
        @Advice.Argument(readOnly = false, value = 1) Runnable task,
        @Advice.Local("wrapper") Wrapper<?> wrapper) {
      if (TPEHelper.shouldPropagate(tpe)) {
        if (TPEHelper.useWrapping(task)) {
          if (task instanceof Wrapper) {
            wrapper = (Wrapper<?>) task;
          }
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
        @Advice.Enter final ContextScope scope,
        @Advice.Argument(value = 1) Runnable task,
        @Advice.Thrown Throwable throwable,
        @Advice.Local("wrapper") Wrapper<?> wrapper) {
      if (throwable != null) {
        TPEHelper.endScope(scope, task);
        if (wrapper != null) {
          wrapper.cancel();
        }
      } else if (scope != null) {
        TPEHelper.setThreadLocalScope(scope, task);
      }
    }
  }

  public static final class AfterExecute {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope afterExecuteEnter(
        @Advice.This final ThreadPoolExecutor tpe,
        @Advice.Argument(readOnly = false, value = 0) Runnable task) {
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
        @Advice.Enter final ContextScope scope, @Advice.Argument(value = 0) Runnable task) {
      if (scope != null) {
        TPEHelper.endScope(scope, task);
      }
    }
  }

  public static final class Remove {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean enter(
        @Advice.This final ThreadPoolExecutor tpe,
        @Advice.Argument(0) Runnable task,
        @Advice.Local("owner") Runnable owner,
        @Advice.Local("continuation") ContextContinuation continuation) {
      if (!TPEHelper.shouldPropagate(tpe)) {
        return false;
      }
      if (task instanceof Wrapper) {
        owner = task;
        return false;
      }
      if (task == null) {
        return false;
      }
      ContextStore<Runnable, State> contextStore =
          InstrumentationContext.get(Runnable.class, State.class);
      for (Runnable queued : tpe.getQueue()) {
        Runnable logical = queued instanceof Wrapper ? ((Wrapper<?>) queued).unwrap() : queued;
        if (task == logical || task.equals(logical)) {
          if (queued instanceof Wrapper) {
            if (tpe.getQueue().remove(queued)) {
              ((Wrapper<?>) queued).cancel();
              return true;
            }
            continue;
          }
          owner = queued;
          State state = contextStore.get(queued);
          continuation = state == null ? null : state.getCancellableContinuation();
          break;
        }
      }
      return false;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void remove(
        @Advice.This final ThreadPoolExecutor tpe,
        @Advice.Enter boolean handled,
        @Advice.Local("owner") Runnable owner,
        @Advice.Local("continuation") ContextContinuation continuation,
        @Advice.Return(readOnly = false) boolean removed) {
      if (handled) {
        removed = true;
        return;
      }
      if (!TPEHelper.shouldPropagate(tpe)) {
        return;
      }
      if (removed) {
        if (owner instanceof Wrapper) {
          Wrapper<?> wrapper = ((Wrapper<?>) owner);
          wrapper.cancel();
        } else if (owner != null) {
          State state = InstrumentationContext.get(Runnable.class, State.class).get(owner);
          if (state != null) {
            state.closeContinuation(continuation);
          }
        }
      }
    }
  }

  public static final class ShutdownNow {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void shutdown(
        @Advice.This final ThreadPoolExecutor tpe, @Advice.Return List<Runnable> tasks) {
      if (tasks != null && TPEHelper.shouldPropagate(tpe)) {
        ContextStore<Runnable, State> contextStore =
            InstrumentationContext.get(Runnable.class, State.class);
        for (ListIterator<Runnable> iterator = tasks.listIterator(); iterator.hasNext(); ) {
          Runnable task = iterator.next();
          if (task instanceof Wrapper) {
            Wrapper<?> wrapper = (Wrapper<?>) task;
            wrapper.cancel();
            iterator.set(wrapper.unwrap());
          } else {
            State state = contextStore.get(task);
            if (state != null) {
              state.closeContinuation(state.getCancellableContinuation());
            }
          }
        }
      }
    }
  }
}
