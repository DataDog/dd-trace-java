package datadog.trace.instrumentation.kotlin.coroutines;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ManagedScope;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.ThreadContextElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ManagedScopeCoroutineContext implements ThreadContextElement<ManagedScope> {

  private static final Key<ManagedScopeCoroutineContext> KEY = new ContextElementKey();
  private final ManagedScope managedScope = AgentTracer.get().delegateManagedScope();

  @Override
  public void restoreThreadContext(
      @NotNull CoroutineContext coroutineContext, ManagedScope oldState) {
    oldState.activate();
  }

  @Override
  public ManagedScope updateThreadContext(@NotNull CoroutineContext coroutineContext) {
    final ManagedScope oldManagedScope = AgentTracer.get().delegateManagedScope();
    oldManagedScope.fetch();

    managedScope.activate();

    return oldManagedScope;
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

  static class ContextElementKey implements Key<ManagedScopeCoroutineContext> {}
}
