package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.instrumentation.kotlin.coroutines.CoroutineContextHelper.getJob;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeState;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.ThreadContextElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Map a scope state to a coroutine context. */
public class ScopeStateCoroutineContext
    implements ThreadContextElement<ScopeState>, Function1<Throwable, Unit> {

  // Marker scope state to handle concurrent completion and restoration missing the completed state
  private static final ScopeState COMPLETED = new ScopeState.NoopScopeState();

  // Maximum time to wait if the coroutine is rescheduled before it has been restored properly
  private static final long MAX_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

  private static final Key<ScopeStateCoroutineContext> KEY = new ContextElementKey();

  private final ScopeState initialScopeState;
  private final AtomicReference<ScopeState> availableScopeState = new AtomicReference<>();
  private volatile AgentScope.Continuation continuation;
  private volatile AgentScope continuationScope;
  private volatile boolean registered = false;

  public ScopeStateCoroutineContext(CoroutineContext coroutineContext) {
    final AgentScope activeScope = AgentTracer.get().activeScope();
    if (activeScope != null && activeScope.isAsyncPropagating()) {
      continuation = activeScope.capture();
      maybeRegisterOnCompletionCallback(coroutineContext);
    } else {
      continuation = null;
    }
    initialScopeState = AgentTracer.get().newScopeState();
    availableScopeState.set(initialScopeState);
  }

  // Called when a coroutine is about to start executing.
  @Override
  public ScopeState updateThreadContext(@NotNull final CoroutineContext coroutineContext) {
    ScopeState scopeState = initialScopeState;
    // There seems to be some weird scheduling bug (IMHO) where a CoroutineContext can be
    // rescheduled if you use a very small delay() so that updateThreadContext can be called on the
    // new thread while restoreThreadContext is still in progress on the old thread and the scope
    // state is not available for us to use.
    // We try to mitigate the issue here by delaying execution for up to MAX_WAIT_NANOS and then not
    // activating the scope state if it's still in use by the old thread.
    long delay = 0;
    long start = 0;
    while (!availableScopeState.compareAndSet(scopeState, null)) {
      // If we've waited too long, then don't try to use the scope state
      if (delay > MAX_WAIT_NANOS) {
        scopeState = null;
        break;
      }
      // If this is the first time around, then update the start time, and the reason for using
      // delay is that we can't check if start is 0, since that is a valid System.nanoTime value
      if (delay == 0) {
        start = System.nanoTime();
      }
      Thread.yield();
      // We can't have a delay that is 0 since we will move the start time if that happens
      delay = Long.max(System.nanoTime() - start, 1);
    }

    if (scopeState != null) {
      scopeState.activate();
      if (continuation != null) {
        continuationScope = continuation.activate();
        continuation = null;
        // Sometimes there is no job available when the continuation is captured so register again
        maybeRegisterOnCompletionCallback(coroutineContext);
      }
    }
    return scopeState;
  }

  // Called when a coroutine is about to stop executing.
  @Override
  public void restoreThreadContext(
      @NotNull final CoroutineContext coroutineContext, final ScopeState oldState) {
    if (oldState != null) {
      // We only need to clean up if we have a continuationScope, since the continuation will
      // already have been activated before we get here
      if (continuationScope != null) {
        Job job = getJob(coroutineContext);
        if (job != null && !job.isActive()) {
          maybeCancelOrClose();
        }
      }
      oldState.restore();
      ScopeState maybeCompleted = availableScopeState.getAndSet(oldState);
      if (maybeCompleted == COMPLETED) {
        // The method for invoke on completion was called while we were processing, and we missed
        // that the job is no longer active, so try to clean up again
        if (availableScopeState.compareAndSet(oldState, null)) {
          activateAndMaybeCancelOrClose(oldState);
          availableScopeState.set(oldState);
        }
      }
    }
  }

  // Called when a coroutine has been completed. Can be executed concurrently.
  @Override
  public Unit invoke(Throwable throwable) {
    ScopeState scopeState = availableScopeState.getAndSet(COMPLETED);
    // If there is no race with execution, then activate the scope state and clean up
    if (scopeState != null && scopeState != COMPLETED) {
      activateAndMaybeCancelOrClose(scopeState);
      availableScopeState.set(scopeState);
    }
    return Unit.INSTANCE;
  }

  private void maybeRegisterOnCompletionCallback(CoroutineContext coroutineContext) {
    if (!registered) {
      // Make sure we clean up on completion
      Job job = getJob(coroutineContext);
      if (job != null) {
        job.invokeOnCompletion(this);
        registered = true;
      }
    }
  }

  private void activateAndMaybeCancelOrClose(ScopeState scopeState) {
    scopeState.activate();
    maybeCancelOrClose();
    scopeState.restore();
  }

  private void maybeCancelOrClose() {
    // We can have either the continuation or the continuationScope stored but not both
    if (continuation != null) {
      continuation.cancel();
      continuation = null;
    } else if (continuationScope != null && continuationScope == AgentTracer.activeScope()) {
      continuationScope.close();
      continuationScope = null;
    }
  }

  @Nullable
  @Override
  public <E extends Element> E get(@NotNull final Key<E> key) {
    return CoroutineContext.Element.DefaultImpls.get(this, key);
  }

  @NotNull
  @Override
  public CoroutineContext minusKey(@NotNull final Key<?> key) {
    return CoroutineContext.Element.DefaultImpls.minusKey(this, key);
  }

  @NotNull
  @Override
  public CoroutineContext plus(@NotNull final CoroutineContext coroutineContext) {
    return CoroutineContext.DefaultImpls.plus(this, coroutineContext);
  }

  @Override
  public <R> R fold(
      R initial, @NotNull Function2<? super R, ? super Element, ? extends R> operation) {
    return CoroutineContext.Element.DefaultImpls.fold(this, initial, operation);
  }

  @NotNull
  @Override
  public Key<?> getKey() {
    return KEY;
  }

  static class ContextElementKey implements Key<ScopeStateCoroutineContext> {}
}
