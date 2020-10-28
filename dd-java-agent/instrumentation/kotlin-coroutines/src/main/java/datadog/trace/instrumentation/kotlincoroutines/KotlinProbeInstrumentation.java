package datadog.trace.instrumentation.kotlincoroutines;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.context.TraceScope;
import java.util.HashMap;
import java.util.Map;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@AutoService(Instrumenter.class)
public class KotlinProbeInstrumentation extends Instrumenter.Default {
  /*
  Kotlin coroutines with suspend functions are a form of cooperative "userland" threading
  (you might also know this pattern as "fibers" or "green threading", where the OS/kernel-level thread
  has no idea of switching between tasks.  Fortunately kotlin exposes hooks for the key events: knowing when
  coroutines are being created, when they are suspended (swapped out/inactive), and when they are resumed (about to
  run again).

  Without this instrumentation, heavy concurrency and usage of kotlin suspend functions will break causality
  and cause nonsensical span parents/context propagation.  This is because a single JVM thread will run a series of
  coroutines in an "arbitrary" order, and a context set by coroutine A (which then gets suspended) will be picked up
  by completely-unrelated coroutine B.

  The basic strategy here is:
  1) Use the DebugProbes callbacks to learn about coroutine create, resume, and suspend operations
  2) Wrap the creation Coroutine and its Context and use that wrapping to add an extra Context "key"
  3) Use the callback for resume and suspend to manipulate our context "key" whereby an appropriate state
     object can be found (tied to the chain of Continuations in the Coroutine).
  */

  public KotlinProbeInstrumentation() {
    super("kotlin-coroutines");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("kotlin.coroutines.jvm.internal.DebugProbesKt");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        named("probeCoroutineCreated").and(takesArguments(1)),
        CoroutineCreatedAdvice.class.getName());
    transformers.put(
        named("probeCoroutineResumed").and(takesArguments(1)),
        CoroutineResumedAdvice.class.getName());
    transformers.put(
        named("probeCoroutineSuspended").and(takesArguments(1)),
        CoroutineSuspendedAdvice.class.getName());
    return transformers;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      KotlinProbeInstrumentation.class.getName() + "$CoroutineWrapper",
      KotlinProbeInstrumentation.class.getName() + "$TraceScopeKey",
      KotlinProbeInstrumentation.class.getName() + "$CoroutineContextWrapper"
    };
  }

  public static class CoroutineCreatedAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Return(readOnly = false) kotlin.coroutines.Continuation retVal) {
      if (!(retVal instanceof CoroutineWrapper)) {
        retVal = new CoroutineWrapper(retVal);
      }
    }
  }

  public static class CoroutineResumedAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(0) final kotlin.coroutines.Continuation continuation) {
      final CoroutineContextWrapper w = continuation.getContext().get(TraceScopeKey.INSTANCE);
      if (w != null) {
        w.tracingResume();
      }
    }
  }

  public static class CoroutineSuspendedAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(0) final kotlin.coroutines.Continuation continuation) {
      final CoroutineContextWrapper w = continuation.getContext().get(TraceScopeKey.INSTANCE);
      if (w != null) {
        w.tracingSuspend();
      }
    }
  }

  public static class TraceScopeKey implements CoroutineContext.Key<CoroutineContextWrapper> {
    public static final TraceScopeKey INSTANCE = new TraceScopeKey();
  }

  public static class CoroutineWrapper implements kotlin.coroutines.Continuation {
    private final Continuation proxy;
    public final CoroutineContextWrapper contextWrapper;

    public CoroutineWrapper(Continuation proxy) {
      this.proxy = proxy;
      this.contextWrapper = new CoroutineContextWrapper(proxy.getContext());
    }

    public String toString() {
      return proxy.toString();
    }

    @NotNull
    @Override
    public CoroutineContext getContext() {
      return contextWrapper;
    }

    @Override
    public void resumeWith(@NotNull Object o) {
      proxy.resumeWith(o);
    }
  }

  public static class CoroutineContextWrapper
      implements CoroutineContext, CoroutineContext.Element {
    private final CoroutineContext proxy;
    private TraceScope currentScope;
    private TraceScope.Continuation currentContinuation;
    // private TraceScope previousScope;
    // private TraceScope.Continuation previousContinuation;

    public CoroutineContextWrapper(CoroutineContext proxy) {
      this.proxy = proxy;
      currentScope = activeScope();
      if (currentScope != null) {
        currentContinuation = activeScope().capture();
      }
    }

    @Override
    public <R> R fold(R r, @NotNull Function2<? super R, ? super Element, ? extends R> function2) {
      return proxy.fold(r, function2);
    }

    @Nullable
    @Override
    public <E extends Element> E get(@NotNull Key<E> key) {
      if (key == TraceScopeKey.INSTANCE) {
        return (E) this;
      }
      return proxy.get(key);
    }

    @NotNull
    @Override
    public CoroutineContext minusKey(@NotNull Key<?> key) {
      // I can't be removed!
      return proxy.minusKey(key);
    }

    @NotNull
    @Override
    public CoroutineContext plus(@NotNull CoroutineContext coroutineContext) {
      return proxy.plus(coroutineContext);
    }

    public String toString() {
      return proxy.toString();
    }

    @NotNull
    @Override
    public Key<?> getKey() {
      return TraceScopeKey.INSTANCE;
    }

    // Actual tracing context-switch logic
    public void tracingSuspend() {
      if (currentScope != null) {
        currentContinuation = currentScope.capture();
      }
      currentScope = activeScope();
    }

    public void tracingResume() {
      if (currentContinuation != null) {
        currentScope = currentContinuation.activate();
        currentScope.setAsyncPropagation(true);
      }
    }
  }
}
