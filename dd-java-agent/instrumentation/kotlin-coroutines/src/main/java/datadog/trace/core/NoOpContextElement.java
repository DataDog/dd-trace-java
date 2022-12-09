package datadog.trace.core;

import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.ThreadContextElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class NoOpContextElement implements ThreadContextElement<Object> {
  static final Key<NoOpContextElement> KEY = new Key<NoOpContextElement>() {};

  public NoOpContextElement() {}

  @Override
  public void restoreThreadContext(@NotNull CoroutineContext coroutineContext, Object oldState) {}

  @Override
  public Object updateThreadContext(@NotNull CoroutineContext coroutineContext) {
    return null;
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
}
