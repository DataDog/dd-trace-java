package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;

import datadog.context.Context;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.ThreadContextElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DatadogCoroutineContext implements ThreadContextElement<Context> {

  public static final Key<DatadogCoroutineContext> KEY = new ContextElementKey();

  private final ContextStore<Job, DatadogCoroutineContextItem> contextItemPerCoroutine;

  public DatadogCoroutineContext(
      final ContextStore<Job, DatadogCoroutineContextItem> contextItemPerCoroutine) {
    this.contextItemPerCoroutine = contextItemPerCoroutine;
  }

  /** Get a context item instance for the coroutine and try to initialize it */
  public void maybeInitialize(final Job coroutine) {
    contextItemPerCoroutine
        .putIfAbsent(coroutine, DatadogCoroutineContextItem::new)
        .maybeInitialize();
  }

  @Override
  public void restoreThreadContext(
      @NotNull final CoroutineContext coroutineContext, final Context oldDatadogContext) {
    oldDatadogContext.swap();
  }

  @Override
  public Context updateThreadContext(@NotNull final CoroutineContext coroutineContext) {
    final Context oldDatadogContext = Context.current();

    final Job coroutine = CoroutineContextHelper.getJob(coroutineContext);
    final DatadogCoroutineContextItem contextItem = contextItemPerCoroutine.get(coroutine);
    if (contextItem != null) {
      contextItem.activate();
    }

    return oldDatadogContext;
  }

  /** If there's a context item for the coroutine then try to close it */
  public void maybeCloseScopeAndCancelContinuation(final Job coroutine) {
    final DatadogCoroutineContextItem contextItem = contextItemPerCoroutine.get(coroutine);
    if (contextItem != null) {
      final Context currentDatadogContext = Context.current();

      contextItem.maybeCloseScopeAndCancelContinuation();
      contextItemPerCoroutine.remove(coroutine);

      currentDatadogContext.swap();
    }
  }

  @Nullable
  @Override
  public <E extends Element> E get(@NotNull final Key<E> key) {
    return Element.DefaultImpls.get(this, key);
  }

  @NotNull
  @Override
  public CoroutineContext minusKey(@NotNull final Key<?> key) {
    return Element.DefaultImpls.minusKey(this, key);
  }

  @NotNull
  @Override
  public CoroutineContext plus(@NotNull final CoroutineContext coroutineContext) {
    return CoroutineContext.DefaultImpls.plus(this, coroutineContext);
  }

  @Override
  public <R> R fold(
      R initial, @NotNull Function2<? super R, ? super Element, ? extends R> operation) {
    return Element.DefaultImpls.fold(this, initial, operation);
  }

  @NotNull
  @Override
  public Key<?> getKey() {
    return KEY;
  }

  static class ContextElementKey implements Key<DatadogCoroutineContext> {}

  public static class DatadogCoroutineContextItem {
    private final Context datadogContext;
    @Nullable private AgentScope.Continuation continuation;
    @Nullable private AgentScope continuationScope;
    private boolean isInitialized = false;

    public DatadogCoroutineContextItem() {
      datadogContext = Context.root();
    }

    public void activate() {
      datadogContext.swap();

      if (continuation != null && continuationScope == null) {
        continuationScope = continuation.activate();
      }
    }

    /**
     * If there is an active scope at the time of invocation, and it is async propagated, then
     * captures the scope's continuation
     */
    public void maybeInitialize() {
      if (!isInitialized) {
        continuation = captureActiveSpan().hold();
        isInitialized = true;
      }
    }

    /**
     * If the context item has a captured scope continuation and an active scope, then closes the
     * scope and cancels the continuation.
     */
    public void maybeCloseScopeAndCancelContinuation() {
      datadogContext.swap();

      if (continuationScope != null) {
        continuationScope.close();
      }
      if (continuation != null) {
        continuation.cancel();
      }
    }
  }
}
