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

  private static final AtomicReferenceFieldUpdater<DatadogThreadContextElement, Exchange> EXCHANGE =
      AtomicReferenceFieldUpdater.newUpdater(
          DatadogThreadContextElement.class, Exchange.class, "exchange");

  private volatile Exchange exchange;
  private volatile ContextContinuation continuation;

  @Nonnull
  @Override
  public Key<?> getKey() {
    return DATADOG_KEY;
  }

  public static void captureDatadogContext(@Nonnull AbstractCoroutine<?> coroutine) {
    DatadogThreadContextElement datadog = coroutine.getContext().get(DATADOG_KEY);
    if (datadog != null && datadog.exchange == null) {
      // record context to use for this coroutine
      Context captured = Context.current();
      datadog.exchange = new Exchange(captured);
      // stop enclosing trace from finishing early
      datadog.continuation = captured.capture();
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
    if (exchange == null) {
      Context captured = Context.current();
      continuation = captured.capture();
      exchange = new Exchange(captured);
    }
    Exchange current = new Exchange(Thread.currentThread(), exchange.context);
    Exchange previous = EXCHANGE.getAndSet(this, current);
    // Read the actual predecessor after claiming ownership, including any completed restoration.
    current.context = previous.context;
    if (previous.thread == current.thread && previous.active) {
      current.parent = previous;
    }
    current.active = true;
    current.originalContext = current.context.swap();
    return current;
  }

  @Override
  public void restoreThreadContext(@Nonnull CoroutineContext coroutineContext, Exchange restored) {
    restored.active = false;
    Context original = restored.originalContext;
    restored.originalContext = null;
    restored.context = original.swap();
    Exchange parent = restored.parent;
    restored.parent = null;
    if (parent != null) {
      parent.context = restored.context;
      // Unwind same-thread nesting only if a newer worker has not taken ownership.
      EXCHANGE.compareAndSet(this, restored, parent);
    }
  }

  /** Publishes scope changes before suspension can resume the coroutine on another worker. */
  public static void beforeSuspension(Continuation<?> continuation) {
    DatadogThreadContextElement element = continuation.getContext().get(DATADOG_KEY);
    if (element == null) {
      return;
    }
    Exchange current = element.exchange;
    if (current != null && current.thread == Thread.currentThread() && current.active) {
      // A swap captures the complete scope stack; Context.current() only exposes the active
      // context.
      Context currentStack = Context.root().swap();
      currentStack.swap();
      current.context = currentStack;
    }
  }

  static final class Exchange {
    final Thread thread;
    volatile Context context;
    // The activating thread owns the remaining fields.
    Context originalContext;
    Exchange parent;
    boolean active;

    Exchange(Context context) {
      this.thread = null;
      this.context = context;
    }

    Exchange(Thread thread, Context context) {
      this.thread = thread;
      this.context = context;
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
