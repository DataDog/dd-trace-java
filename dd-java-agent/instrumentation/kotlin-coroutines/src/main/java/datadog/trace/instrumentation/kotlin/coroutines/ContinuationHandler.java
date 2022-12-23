package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.instrumentation.kotlin.coroutines.CoroutineContextHelper.getJob;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeState;
import kotlin.Unit;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.Nullable;

public class ContinuationHandler implements Function1<Throwable, Unit> {

  private final ScopeState coroutineScopeState;
  private final AgentScope.Continuation continuation;
  @Nullable private AgentScope continuationScope;

  ContinuationHandler(
      final ScopeState coroutineScopeState, final AgentScope.Continuation continuation) {
    this.coroutineScopeState = coroutineScopeState;
    this.continuation = continuation;
  }

  public void activate() {
    continuationScope = continuation.activate();
  }

  public boolean isActive() {
    return continuationScope != null;
  }

  public void register(final CoroutineContext coroutineContext) {
    getJob(coroutineContext).invokeOnCompletion(this);
  }

  @Override
  public Unit invoke(final Throwable throwable) {
    closeScopeAndCancelContinuation();

    return Unit.INSTANCE;
  }

  private void closeScopeAndCancelContinuation() {
    final ScopeState currentThreadScopeState = AgentTracer.get().newScopeState();
    currentThreadScopeState.fetchFromActive();

    coroutineScopeState.activate();

    if (continuationScope != null) {
      continuationScope.close();
    }
    continuation.cancel();

    currentThreadScopeState.activate();
  }
}
