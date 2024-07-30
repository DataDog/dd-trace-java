package datadog.trace.instrumentation.kotlin.coroutines;

import datadog.context.Context;
import datadog.context.ContextScope;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.ThreadContextElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DDContextElement implements ThreadContextElement<ContextScope> {
  public static final Key<DDContextElement> KEY = new Key<DDContextElement>() {};

  private final Context context;

  public DDContextElement(Context context) {
    this.context = context;
  }

  public static Context contextFromCoroutineContext(CoroutineContext coroutineContext) {
    DDContextElement ddContextElement = coroutineContext.get(KEY);
    return ddContextElement == null ? Context.empty() : ddContextElement.context;
  }

  @Override
  public void restoreThreadContext(@NotNull CoroutineContext coroutineContext, ContextScope scope) {
    scope.close();
  }

  @Override
  public ContextScope updateThreadContext(@NotNull CoroutineContext coroutineContext) {
    return this.context.makeCurrent();
  }

  @NotNull
  @Override
  public Key<?> getKey() {
    return KEY;
  }

  @Nullable
  @Override
  public <E extends Element> E get(@NotNull Key<E> key) {
    return Element.DefaultImpls.get(this, key);
  }

  @Override
  public <R> R fold(
      R initial, @NotNull Function2<? super R, ? super Element, ? extends R> operation) {
    return Element.DefaultImpls.fold(this, initial, operation);
  }

  @NotNull
  @Override
  public CoroutineContext minusKey(@NotNull Key<?> key) {
    return Element.DefaultImpls.minusKey(this, key);
  }

  @NotNull
  @Override
  public CoroutineContext plus(@NotNull CoroutineContext coroutineContext) {
    return CoroutineContext.DefaultImpls.plus(this, coroutineContext);
  }
}
