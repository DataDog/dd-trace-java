package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.instrumentation.java.concurrent.AbstractExecutorInstrumentation.EXEC_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.QueueTimerHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TPEHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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
@AutoService(Instrumenter.class)
public final class ThreadPoolExecutorInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy, ExcludeFilterProvider {

  private static final String TPE = "java.util.concurrent.ThreadPoolExecutor";

  // executors which do their own wrapping before calling super,
  // leading to double wrapping, once at the child level and once
  // in ThreadPoolExecutor
  private static final ElementMatcher<MethodDescription> NO_WRAPPING_BEFORE_DELEGATION =
      not(
          isDeclaredBy(
              namedOneOf("org.elasticsearch.common.util.concurrent.EsThreadPoolExecutor")));

  public ThreadPoolExecutorInstrumentation() {
    super(EXEC_NAME);
  }

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
  public Map<String, String> contextStore() {
    final Map<String, String> stores = new HashMap<>();
    stores.put(TPE, Boolean.class.getName());
    stores.put(Runnable.class.getName(), State.class.getName());
    return Collections.unmodifiableMap(stores);
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isConstructor(), getClass().getName() + "$Init");
    transformation.applyAdvice(
        named("execute")
            .and(isMethod())
            .and(NO_WRAPPING_BEFORE_DELEGATION)
            .and(takesArgument(0, named(Runnable.class.getName()))),
        getClass().getName() + "$Execute");
    transformation.applyAdvice(
        named("beforeExecute")
            .and(isMethod())
            .and(takesArgument(1, named(Runnable.class.getName()))),
        getClass().getName() + "$BeforeExecute");
    transformation.applyAdvice(
        named("afterExecute")
            .and(isMethod())
            .and(takesArgument(0, named(Runnable.class.getName()))),
        getClass().getName() + "$AfterExecute");
    transformation.applyAdvice(
        named("remove").and(isMethod()).and(returns(Runnable.class)),
        getClass().getName() + "$Remove");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return Collections.singletonMap(
        RUNNABLE,
        Arrays.asList(
            "datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper",
            "datadog.trace.bootstrap.instrumentation.java.concurrent.ComparableRunnable"));
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
          ContextStore<Runnable, State> contextStore =
              InstrumentationContext.get(Runnable.class, State.class);
          TPEHelper.capture(contextStore, task);
          QueueTimerHelper.startQueuingTimer(contextStore, tpe.getClass(), task);
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
