package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.instrumentation.kotlin.coroutines.CoroutineContextHelper.getJob;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeState;
import kotlin.Unit;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.ThreadContextElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ScopeStateCoroutineContext implements ThreadContextElement<ScopeState> {

  private static final Key<ScopeStateCoroutineContext> KEY = new ContextElementKey();
  private final ScopeState scopeState;
  @Nullable private ContinuationHandler continuationHandler;

  public ScopeStateCoroutineContext() {
    final AgentScope activeScope = AgentTracer.get().activeScope();
    if (activeScope != null) {
      activeScope.setAsyncPropagation(true);
      continuationHandler = new ContinuationHandler(activeScope.captureConcurrent());
    }
    scopeState = AgentTracer.get().newScopeState();
  }

  @Override
  public void restoreThreadContext(
      @NotNull CoroutineContext coroutineContext, ScopeState oldState) {
    oldState.activate();
  }

  @Override
  public ScopeState updateThreadContext(@NotNull CoroutineContext coroutineContext) {
    final ScopeState oldScopeState = AgentTracer.get().newScopeState();
    oldScopeState.fetchFromActive();

    scopeState.activate();

    if (continuationHandler != null && !continuationHandler.isActive()) {
      continuationHandler.activate();
      continuationHandler.register(coroutineContext);
    }

    return oldScopeState;
  }

  public static class ContinuationHandler implements Function1<Throwable, Unit> {

    private final AgentScope.Continuation continuation;
    @Nullable private AgentScope continuationScope;

    ContinuationHandler(final AgentScope.Continuation continuation) {
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
    public Unit invoke(Throwable throwable) {
      if (continuationScope != null) {
        continuationScope.close();
      }
      continuation.cancel();

      return Unit.INSTANCE;
    }
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
