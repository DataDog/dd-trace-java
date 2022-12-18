package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.instrumentation.kotlin.coroutines.CoroutineContextHelper.getJob;

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

  private static final Key<ScopeStateCoroutineContext> KEY = new ContextElementKey();
  private AgentScope.Continuation continuation;
  private AgentScope continuationScope;
  private final ScopeState scopeState;

  public ScopeStateCoroutineContext() {
    final AgentScope activeScope = AgentTracer.get().activeScope();
    if (activeScope != null) {
      activeScope.setAsyncPropagation(true);
      continuation = activeScope.captureConcurrent();
    }
    scopeState = AgentTracer.get().newScopeState();
  }

  @Override
  public void restoreThreadContext(
      @NotNull CoroutineContext coroutineContext, ScopeState oldState) {
    if (continuation != null) {
      final Job job = getJob(coroutineContext);
      if (!job.isActive()) {
        continuationScope.close();
        continuation.cancel();
      }
    }

    oldState.activate();
  }

  @Override
  public ScopeState updateThreadContext(@NotNull CoroutineContext coroutineContext) {
    final ScopeState oldScopeState = AgentTracer.get().newScopeState();
    oldScopeState.fetchFromActive();

    scopeState.activate();
    if (continuation != null && continuationScope == null) {
      continuationScope = continuation.activate();
    }

    return oldScopeState;
  }

  @Nullable
  @Override
  public <E extends Element> E get(@NotNull Key<E> key) {
    return CoroutineContext.Element.DefaultImpls.get(this, key);
  }

  @NotNull
  @Override
  public CoroutineContext minusKey(@NotNull Key<?> key) {
    return CoroutineContext.Element.DefaultImpls.minusKey(this, key);
  }

  @NotNull
  @Override
  public CoroutineContext plus(@NotNull CoroutineContext coroutineContext) {
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
