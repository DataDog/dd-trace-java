package datadog.trace.instrumentation.kotlin.coroutines;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.AbstractCoroutine;
import kotlinx.coroutines.ThreadContextElement;

/** Manages the Datadog context for coroutines, switching contexts as coroutines switch threads. */
public final class DatadogThreadContextElement implements ThreadContextElement<Context> {
  private static final AtomicReferenceFieldUpdater<DatadogThreadContextElement, ContextContinuation>
      CONTINUATION =
          AtomicReferenceFieldUpdater.newUpdater(
              DatadogThreadContextElement.class, ContextContinuation.class, "continuation");

  private static final CoroutineContext.Key<DatadogThreadContextElement> DATADOG_KEY =
      new CoroutineContext.Key<DatadogThreadContextElement>() {};

  private static final boolean LEGACY_CONTEXT_MANAGER =
      InstrumenterConfig.get().isLegacyContextManagerEnabled();

  /** Gives each new coroutine its own element, so coroutines never share Datadog context. */
  public static CoroutineContext addDatadogElement(CoroutineContext coroutineContext) {
    DatadogThreadContextElement existing = coroutineContext.get(DATADOG_KEY);
    if (existing == null) {
      return coroutineContext.plus(new DatadogThreadContextElement(null));
    }
    // a context passed from another coroutine starts from that coroutine's current context
    DatadogThreadContextElement fresh = new DatadogThreadContextElement(existing.context);
    // keep the element's position, so elements added after it can still update scopes on top
    return coroutineContext.<CoroutineContext>fold(
        EmptyCoroutineContext.INSTANCE,
        (replaced, element) -> replaced.plus(element == existing ? fresh : element));
  }

  @Nullable private final Context seed;
  private volatile Context context;
  private volatile ContextContinuation continuation;

  private DatadogThreadContextElement(@Nullable Context seed) {
    this.seed = seed;
  }

  @Nonnull
  @Override
  public Key<?> getKey() {
    return DATADOG_KEY;
  }

  public static void captureDatadogContext(@Nonnull AbstractCoroutine<?> coroutine) {
    DatadogThreadContextElement datadog = coroutine.getContext().get(DATADOG_KEY);
    if (datadog != null && datadog.context == null) {
      datadog.captureInitialContext();
    }
  }

  private synchronized void captureInitialContext() {
    if (context == null) {
      Context initial = seed != null ? seed : Context.current();
      // stop enclosing trace from finishing early
      continuation = initial.capture();
      if (LEGACY_CONTEXT_MANAGER) {
        // workers share this coroutine's scope stack, rather than copying it between them
        initial = AgentTracer.get().sharedContextStack(initial);
      }
      context = initial;
    }
  }

  public static void cancelDatadogContext(@Nonnull AbstractCoroutine<?> coroutine) {
    DatadogThreadContextElement datadog = coroutine.getContext().get(DATADOG_KEY);
    ContextContinuation continuation =
        datadog == null ? null : CONTINUATION.getAndSet(datadog, null);
    if (continuation != null) {
      // release enclosing trace now the coroutine has completed
      continuation.release();
    }
  }

  @Override
  public Context updateThreadContext(@Nonnull CoroutineContext coroutineContext) {
    if (context == null) {
      captureInitialContext();
    }
    return context.swap();
  }

  @Override
  public void restoreThreadContext(
      @Nonnull CoroutineContext coroutineContext, Context originalContext) {
    Context coroutineContextAfter = originalContext.swap();
    if (!LEGACY_CONTEXT_MANAGER) {
      // legacy workers share the coroutine's scope stack, so there's nothing to save
      context = coroutineContextAfter;
    }
  }

  @Nonnull
  @Override
  public CoroutineContext plus(@Nonnull CoroutineContext coroutineContext) {
    return CoroutineContext.DefaultImpls.plus(this, coroutineContext);
  }

  @Override
  public <R> R fold(
      R initial, @Nonnull Function2<? super R, ? super Element, ? extends R> operation) {
    return CoroutineContext.Element.DefaultImpls.fold(this, initial, operation);
  }

  @Nullable
  @Override
  public <E extends Element> E get(@Nonnull Key<E> key) {
    return CoroutineContext.Element.DefaultImpls.get(this, key);
  }

  @Nonnull
  @Override
  public CoroutineContext minusKey(@Nonnull Key<?> key) {
    return CoroutineContext.Element.DefaultImpls.minusKey(this, key);
  }
}
