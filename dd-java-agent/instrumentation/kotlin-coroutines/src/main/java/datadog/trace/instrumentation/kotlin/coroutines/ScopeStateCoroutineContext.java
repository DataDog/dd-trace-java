package datadog.trace.instrumentation.kotlin.coroutines;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeState;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.ThreadContextElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ScopeStateCoroutineContext implements ThreadContextElement<ScopeState> {

  public static final Key<ScopeStateCoroutineContext> KEY = new ContextElementKey();

  private final ContextStore<Job, ScopeStateCoroutineContextItem> contextItemPerCoroutine;

  public ScopeStateCoroutineContext(
      final ContextStore<Job, ScopeStateCoroutineContextItem> contextItemPerCoroutine) {
    this.contextItemPerCoroutine = contextItemPerCoroutine;
  }

  /** Get a context item instance for the coroutine and try to initialize it */
  public void maybeInitialize(final Job coroutine) {
    contextItemPerCoroutine
        .putIfAbsent(coroutine, ScopeStateCoroutineContextItem::new)
        .maybeInitialize();
  }

  @Override
  public void restoreThreadContext(
      @NotNull final CoroutineContext coroutineContext, final ScopeState oldState) {
    oldState.activate();
  }

  @Override
  public ScopeState updateThreadContext(@NotNull final CoroutineContext coroutineContext) {
    final ScopeState oldScopeState = AgentTracer.get().newScopeState();
    oldScopeState.fetchFromActive();

    final Job coroutine = CoroutineContextHelper.getJob(coroutineContext);
    final ScopeStateCoroutineContextItem contextItem = contextItemPerCoroutine.get(coroutine);
    if (contextItem != null) {
      contextItem.activate();
    }

    return oldScopeState;
  }

  /** If there's a context item for the coroutine then try to close it */
  public void maybeCloseScopeAndCancelContinuation(final Job coroutine) {
    final ScopeStateCoroutineContextItem contextItem = contextItemPerCoroutine.get(coroutine);
    if (contextItem != null) {
      final ScopeState currentThreadScopeState = AgentTracer.get().newScopeState();
      currentThreadScopeState.fetchFromActive();

      contextItem.maybeCloseScopeAndCancelContinuation();
      contextItemPerCoroutine.remove(coroutine);

      currentThreadScopeState.activate();
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

  static class ContextElementKey implements Key<ScopeStateCoroutineContext> {}

  public static class ScopeStateCoroutineContextItem {
    private final ScopeState coroutineScopeState;
    @Nullable private AgentScope.Continuation continuation;
    @Nullable private AgentScope continuationScope;
    private boolean isInitialized = false;

    public ScopeStateCoroutineContextItem() {
      coroutineScopeState = AgentTracer.get().newScopeState();
    }

    public void activate() {
      coroutineScopeState.activate();

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
        final AgentScope activeScope = AgentTracer.get().activeScope();
        if (activeScope != null && activeScope.isAsyncPropagating()) {
          continuation = activeScope.captureConcurrent();
        }
        isInitialized = true;
      }
    }

    /**
     * If the context item has a captured scope continuation and an active scope, then closes the
     * scope and cancels the continuation.
     */
    public void maybeCloseScopeAndCancelContinuation() {
      coroutineScopeState.activate();

      if (continuationScope != null) {
        continuationScope.close();
      }
      if (continuation != null) {
        continuation.cancel();
      }
    }
  }
}
