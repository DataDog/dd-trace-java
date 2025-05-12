package datadog.trace.instrumentation.kotlin.coroutines;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeState;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.AbstractCoroutine;
import kotlinx.coroutines.ThreadContextElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Manages the Datadog context for coroutines, switching contexts as coroutines switch threads. */
public final class DatadogThreadContextElement implements ThreadContextElement<ScopeState> {
  private static final CoroutineContext.Key<DatadogThreadContextElement> DATADOG_KEY =
      new CoroutineContext.Key<DatadogThreadContextElement>() {};

  public static CoroutineContext addDatadogElement(CoroutineContext coroutineContext) {
    if (coroutineContext.get(DATADOG_KEY) != null) {
      return coroutineContext; // already added
    }
    return coroutineContext.plus(new DatadogThreadContextElement());
  }

  private ScopeState scopeState;
  private AgentScope.Continuation continuation;

  @NotNull
  @Override
  public Key<?> getKey() {
    return DATADOG_KEY;
  }

  public static void captureDatadogContext(@NotNull AbstractCoroutine<?> coroutine) {
    DatadogThreadContextElement datadogContext = coroutine.getContext().get(DATADOG_KEY);
    if (datadogContext != null && datadogContext.scopeState == null) {
      // copy scope stack to use for this coroutine
      datadogContext.scopeState = AgentTracer.get().oldScopeState().copy();
      // stop enclosing trace from finishing early
      datadogContext.continuation = AgentTracer.captureActiveSpan();
    }
  }

  public static void cancelDatadogContext(@NotNull AbstractCoroutine<?> coroutine) {
    DatadogThreadContextElement datadogContext = coroutine.getContext().get(DATADOG_KEY);
    if (datadogContext != null && datadogContext.continuation != null) {
      // release enclosing trace now the coroutine has completed
      datadogContext.continuation.cancel();
    }
  }

  @Override
  public ScopeState updateThreadContext(@NotNull CoroutineContext coroutineContext) {
    ScopeState oldState = AgentTracer.get().oldScopeState();
    if (scopeState == null) {
      // copy scope stack to use for this coroutine
      scopeState = oldState.copy();
      // stop enclosing trace from finishing early
      continuation = AgentTracer.captureActiveSpan();
    }
    scopeState.activate(); // swap in the coroutine's scope stack
    return oldState;
  }

  @Override
  public void restoreThreadContext(
      @NotNull CoroutineContext coroutineContext, ScopeState oldState) {
    oldState.activate(); // swap bock the original scope stack
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
}
