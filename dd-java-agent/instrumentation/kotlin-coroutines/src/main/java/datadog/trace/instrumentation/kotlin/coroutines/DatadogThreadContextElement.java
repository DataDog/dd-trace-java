package datadog.trace.instrumentation.kotlin.coroutines;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.AbstractCoroutine;
import kotlinx.coroutines.ThreadContextElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Manages the Datadog context for coroutines, switching contexts as coroutines switch threads. */
public final class DatadogThreadContextElement implements ThreadContextElement<Context> {
  private static final CoroutineContext.Key<DatadogThreadContextElement> DATADOG_KEY =
      new CoroutineContext.Key<DatadogThreadContextElement>() {};

  public static CoroutineContext addDatadogElement(CoroutineContext coroutineContext) {
    if (coroutineContext.get(DATADOG_KEY) != null) {
      return coroutineContext; // already added
    }
    return coroutineContext.plus(new DatadogThreadContextElement());
  }

  private Context context;
  private AgentScope.Continuation continuation;

  @NotNull
  @Override
  public Key<?> getKey() {
    return DATADOG_KEY;
  }

  public static void captureDatadogContext(@NotNull AbstractCoroutine<?> coroutine) {
    DatadogThreadContextElement datadog = coroutine.getContext().get(DATADOG_KEY);
    if (datadog != null && datadog.context == null) {
      // record context to use for this coroutine
      datadog.context = Context.current();
      // stop enclosing trace from finishing early
      datadog.continuation = AgentTracer.captureActiveSpan();
    }
  }

  public static void cancelDatadogContext(@NotNull AbstractCoroutine<?> coroutine) {
    DatadogThreadContextElement datadog = coroutine.getContext().get(DATADOG_KEY);
    if (datadog != null && datadog.continuation != null) {
      // release enclosing trace now the coroutine has completed
      datadog.continuation.cancel();
    }
  }

  @Override
  public Context updateThreadContext(@NotNull CoroutineContext coroutineContext) {
    if (context == null) {
      // record context to use for this coroutine
      context = Context.current();
      // stop enclosing trace from finishing early
      continuation = AgentTracer.captureActiveSpan();
    }
    return context.swap();
  }

  @Override
  public void restoreThreadContext(
      @NotNull CoroutineContext coroutineContext, Context originalContext) {
    context = originalContext.swap();
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
