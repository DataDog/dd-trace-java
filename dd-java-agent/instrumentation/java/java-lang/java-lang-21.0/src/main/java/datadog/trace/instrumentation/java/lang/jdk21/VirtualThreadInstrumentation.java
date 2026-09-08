package datadog.trace.instrumentation.java.lang.jdk21;

import static datadog.context.Context.current;
import static datadog.context.Context.root;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadHelper.VIRTUAL_THREAD_CLASS_NAME;
import static datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadHelper.VIRTUAL_THREAD_STATE_CLASS_NAME;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadState;
import java.util.Collection;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;

/**
 * Instruments {@code VirtualThread} to propagate the context across mount/unmount cycles using
 * {@link Context#swap()}, and {@link ContextContinuation} to prevent context scope to complete
 * before the thread finishes.
 *
 * <p>The lifecycle is as follows:
 *
 * <ol>
 *   <li>{@code init()}: captures the current {@link Context} and an {@link ContextContinuation} to
 *       prevent the enclosing context scope from completing early.
 *   <li>On JDK 22 and later, {@code run(Runnable)} seeds the virtual thread's saved context once
 *       and retains its state in the continuation frame across park/unpark cycles.
 *   <li>{@code mount()} / {@code unmount()}: rebind and clear carrier-local profiler context. The
 *       state-backed swap path is retained on JDK 21 and when context listeners require per-mount
 *       notification.
 *   <li>{@code afterDone()} / {@code afterTerminate()} for early VirtualThread support: cancels the
 *       help continuation, releasing the context scope to be closed.
 * </ol>
 *
 * <p>On JDK 22 and later, {@code run(Runnable)} is the one-shot continuation body and executes
 * after the first mount, when the current thread is the virtual thread. Its advice local is
 * preserved with the continuation across yields. {@code runContinuation()} cannot serve this
 * purpose because it executes once per mount and starts while the carrier is still the current
 * thread. JDK 21 retains the per-mount path because this internal ordering differs across its
 * update releases.
 *
 * @see VirtualThreadState
 */
@SuppressWarnings("unused")
@AutoService(InstrumenterModule.class)
public final class VirtualThreadInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForSingleType,
        Instrumenter.HasMethodAdvice,
        ExcludeFilterProvider {

  // Preload classes used by Context.swap() to avoid class loading on the virtual thread mount path.
  // DatadogClassLoader loads these from a JarFile using synchronized I/O, which pins
  // virtual thread carrier threads and can deadlock the application.
  private static final String[] PRELOAD_CLASS_NAMES = {
    "datadog.trace.core.scopemanager.ScopeContext", "datadog.trace.core.scopemanager.ScopeStack"
  };

  public VirtualThreadInstrumentation() {
    super("java-lang", "java-lang-21", "virtual-thread");
  }

  @Override
  public String[] preloadClassNames() {
    return PRELOAD_CLASS_NAMES;
  }

  @Override
  public String instrumentedType() {
    return VIRTUAL_THREAD_CLASS_NAME;
  }

  @Override
  public boolean isEnabled() {
    return JavaVirtualMachine.isJavaVersionAtLeast(21) && super.isEnabled();
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    // VirtualThread context is managed directly across its internal lifecycle.
    return singletonMap(RUNNABLE, singletonList(VIRTUAL_THREAD_CLASS_NAME));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(VIRTUAL_THREAD_CLASS_NAME, VIRTUAL_THREAD_STATE_CLASS_NAME);
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    if (JavaVirtualMachine.isJavaVersionAtLeast(22)) {
      transformer.applyAdvice(
          isMethod().and(named("run")).and(takesArguments(Runnable.class)).and(returns(void.class)),
          getClass().getName() + "$Run");
    }
    transformer.applyAdvice(isMethod().and(named("mount")), getClass().getName() + "$Mount");
    transformer.applyAdvice(isMethod().and(named("unmount")), getClass().getName() + "$Unmount");
    transformer.applyAdvice(
        isMethod().and(named("afterDone")).and(takesArguments(boolean.class)),
        getClass().getName() + "$AfterDone");
    transformer.applyAdvice(
        isMethod().and(named("afterTerminate")).and(takesArguments(boolean.class, boolean.class)),
        getClass().getName() + "$AfterDone");
  }

  public static final class Construct {
    @OnMethodExit(suppress = Throwable.class)
    public static void afterInit(@Advice.This Object virtualThread) {
      Context context = current();
      if (context == root()) {
        return; // No active context to propagate, avoid creating state
      }
      VirtualThreadState state = new VirtualThreadState(context, captureActiveSpan());
      ContextStore<Object, Object> store =
          InstrumentationContext.get(VIRTUAL_THREAD_CLASS_NAME, VIRTUAL_THREAD_STATE_CLASS_NAME);
      store.put(virtualThread, state);
    }
  }

  public static final class Run {
    @OnMethodEnter(suppress = Throwable.class)
    public static void onRun(
        @Advice.This Object virtualThread,
        @Advice.Local("virtualThreadState") VirtualThreadState state) {
      if (!VirtualThreadState.usePerMountContext()) {
        ContextStore<Object, VirtualThreadState> store =
            InstrumentationContext.get(VIRTUAL_THREAD_CLASS_NAME, VIRTUAL_THREAD_STATE_CLASS_NAME);
        state = store.get(virtualThread);
        if (state != null) {
          state.onRun();
        }
      }
    }

    @OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterRun(@Advice.Local("virtualThreadState") VirtualThreadState state) {
      if (state != null) {
        state.afterRun();
      }
    }
  }

  public static final class Mount {
    @OnMethodExit(suppress = Throwable.class)
    public static void onMount(@Advice.This Object virtualThread) {
      if (VirtualThreadState.usePerMountContext()) {
        ContextStore<Object, VirtualThreadState> store =
            InstrumentationContext.get(VIRTUAL_THREAD_CLASS_NAME, VIRTUAL_THREAD_STATE_CLASS_NAME);
        VirtualThreadState state = store.get(virtualThread);
        if (state != null) {
          state.onMount();
        }
      } else {
        VirtualThreadState.onMountWithoutStore();
      }
    }
  }

  public static final class Unmount {
    @OnMethodEnter(suppress = Throwable.class)
    public static void onUnmount(@Advice.This Object virtualThread) {
      if (VirtualThreadState.usePerMountContext()) {
        ContextStore<Object, VirtualThreadState> store =
            InstrumentationContext.get(VIRTUAL_THREAD_CLASS_NAME, VIRTUAL_THREAD_STATE_CLASS_NAME);
        VirtualThreadState state = store.get(virtualThread);
        if (state != null) {
          state.onUnmount();
        }
      } else {
        VirtualThreadState.onUnmountWithoutStore();
      }
    }
  }

  public static final class AfterDone {
    @OnMethodEnter(suppress = Throwable.class)
    public static void onDone(@Advice.This Object virtualThread) {
      ContextStore<Object, VirtualThreadState> store =
          InstrumentationContext.get(VIRTUAL_THREAD_CLASS_NAME, VIRTUAL_THREAD_STATE_CLASS_NAME);
      VirtualThreadState state = store.remove(virtualThread);
      if (state != null) {
        state.onTerminate();
      }
    }
  }
}
