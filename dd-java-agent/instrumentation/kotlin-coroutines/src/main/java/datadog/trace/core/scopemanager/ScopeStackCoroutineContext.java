package datadog.trace.core.scopemanager;

import static datadog.trace.bootstrap.instrumentation.api.ScopeSource.INSTRUMENTATION;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.scopemanager.ContinuableScopeManager.ScopeStack;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.ThreadContextElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ScopeStackCoroutineContext implements ThreadContextElement<ScopeStack> {

  static final Key<ScopeStackCoroutineContext> KEY = new Key<ScopeStackCoroutineContext>() {};
  private final ContinuableScopeManager scopeManager;
  private final ScopeStack scopeStack;
  @Nullable private final AgentSpan span;

  public ScopeStackCoroutineContext(ContinuableScopeManager scopeManager) {
    this.scopeManager = scopeManager;
    this.span = scopeManager.activeSpan();
    /*
     * initial scope stack for the context element should be empty to prevent the spans created within the coroutine
     * from having a wrong parent span
     */
    this.scopeStack = scopeManager.tlsScopeStack.initialValue();
  }

  @Override
  public void restoreThreadContext(
      @NotNull CoroutineContext coroutineContext, ScopeStack oldState) {
    scopeManager.tlsScopeStack.set(oldState);
  }

  @Override
  public ScopeStack updateThreadContext(@NotNull CoroutineContext coroutineContext) {
    final ScopeStack oldScopeStack = scopeManager.tlsScopeStack.get();
    scopeManager.tlsScopeStack.set(scopeStack);

    if (scopeStack.depth() == 0 && span != null) {
      /*
       * This is necessary for the spans created within the coroutine to properly inherit the spans hierarchy.
       * It's not necessary to close the scope created here as it will be destroyed along with the context element and
       * the scope stack.
       */
      scopeManager.activate(span, INSTRUMENTATION);
    }

    return oldScopeStack;
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
