package datadog.trace.instrumentation.kotlin.coroutines;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.AbstractCoroutine;
import kotlinx.coroutines.ThreadContextElement;

/** Manages the Datadog context for coroutines, switching contexts as coroutines switch threads. */
public final class DatadogThreadContextElement
    implements ThreadContextElement<DatadogThreadContextElement.Exchange> {
  private static final AtomicReferenceFieldUpdater<DatadogThreadContextElement, ContextContinuation>
      CONTINUATION =
          AtomicReferenceFieldUpdater.newUpdater(
              DatadogThreadContextElement.class, ContextContinuation.class, "continuation");

  private static final CoroutineContext.Key<DatadogThreadContextElement> DATADOG_KEY =
      new CoroutineContext.Key<DatadogThreadContextElement>() {};

  public static CoroutineContext addDatadogElement(CoroutineContext coroutineContext) {
    if (coroutineContext.get(DATADOG_KEY) != null) {
      return coroutineContext; // already added
    }
    return coroutineContext.plus(new DatadogThreadContextElement());
  }

  private static final AtomicReferenceFieldUpdater<DatadogThreadContextElement, Context> CONTEXT =
      AtomicReferenceFieldUpdater.newUpdater(
          DatadogThreadContextElement.class, Context.class, "context");

  private volatile Context context;
  private volatile ContextContinuation continuation;

  @Nonnull
  @Override
  public Key<?> getKey() {
    return DATADOG_KEY;
  }

  public static void captureDatadogContext(@Nonnull AbstractCoroutine<?> coroutine) {
    DatadogThreadContextElement datadog = coroutine.getContext().get(DATADOG_KEY);
    if (datadog != null && datadog.context == null) {
      // record context to use for this coroutine
      datadog.context = Context.current();
      // stop enclosing trace from finishing early
      datadog.continuation = datadog.context.capture();
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
  public Exchange updateThreadContext(@Nonnull CoroutineContext coroutineContext) {
    if (context == null) {
      // record context to use for this coroutine
      context = Context.current();
      // stop enclosing trace from finishing early
      continuation = context.capture();
    }
    Context resumed = context;
    return new Exchange(resumed.swap(), resumed);
  }

  @Override
  public void restoreThreadContext(@Nonnull CoroutineContext coroutineContext, Exchange exchange) {
    Context suspended = exchange.originalContext.swap();
    // An early suspension snapshot or a newer restore takes precedence over this worker.
    CONTEXT.compareAndSet(this, exchange.resumedContext, suspended);
  }

  /** Publishes scope changes before suspension can resume the coroutine on another worker. */
  public static void beforeSuspension(Continuation<?> continuation) {
    DatadogThreadContextElement element = continuation.getContext().get(DATADOG_KEY);
    if (element != null) {
      // A swap captures the complete scope stack; Context.current() only exposes the active
      // context.
      Context currentStack = Context.root().swap();
      currentStack.swap();
      element.context = currentStack;
    }
  }

  static final class Exchange {
    final Context originalContext;
    final Context resumedContext;

    Exchange(Context originalContext, Context resumedContext) {
      this.originalContext = originalContext;
      this.resumedContext = resumedContext;
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
