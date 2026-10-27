package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.instrumentation.kotlin.coroutines.DatadogThreadContextElement.addDatadogElement;
import static datadog.trace.instrumentation.kotlin.coroutines.DatadogThreadContextElement.cancelDatadogContext;
import static datadog.trace.instrumentation.kotlin.coroutines.DatadogThreadContextElement.captureDatadogContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;
import kotlin.coroutines.AbstractCoroutineContextElement;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.ContinuationInterceptor;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.ThreadContextElement;
import kotlinx.coroutines.internal.ScopeCoroutine;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/** Context replacement checks shared by the oldest and latest dependency suites. */
public abstract class AbstractDatadogThreadContextElementTest {
  @Test
  void completionReleasesOwnerWithoutAllowingAnotherCoroutineToClaimElement() throws Exception {
    DatadogThreadContextElement element =
        (DatadogThreadContextElement) addDatadogElement(EmptyCoroutineContext.INSTANCE);
    Continuation<Unit> completion =
        new Continuation<Unit>() {
          @Override
          public CoroutineContext getContext() {
            return EmptyCoroutineContext.INSTANCE;
          }

          @Override
          public void resumeWith(Object result) {}
        };
    ScopeCoroutine<Unit> owner = new ScopeCoroutine<>(element, completion);
    captureDatadogContext(owner);
    Field ownerField = DatadogThreadContextElement.class.getDeclaredField("owner");
    ownerField.setAccessible(true);
    assertSame(owner, ownerField.get(element));

    ScopeCoroutine<Unit> scoped = new ScopeCoroutine<>(owner.getContext(), completion);
    captureDatadogContext(scoped);
    scoped.resumeWith(Unit.INSTANCE);
    cancelDatadogContext(scoped);
    assertSame(owner, ownerField.get(element), "Scoped completion must preserve the owner");

    owner.resumeWith(Unit.INSTANCE);
    cancelDatadogContext(owner);
    Object completed = ownerField.get(element);
    assertNotNull(completed, "Completed elements must remain distinct from uninitialized elements");
    assertNotSame(owner, completed, "Completed elements must release the owning coroutine");

    ScopeCoroutine<Unit> later = new ScopeCoroutine<>(element, completion);
    captureDatadogContext(later);
    later.resumeWith(Unit.INSTANCE);
    cancelDatadogContext(later);
    assertSame(
        completed, ownerField.get(element), "Completed elements must not acquire a new owner");
  }

  @TableTest({
    "Scenario          | Ordinary before | Ordinary after | Hooks before | Hooks after | Dispatcher | Custom view",
    "Singleton         | 0               | 0              | 0            | 0           | false      | false      ",
    "Ordinary middle   | 2               | 2              | 0            | 0           | true       | false      ",
    "Later hook        | 1               | 1              | 0            | 1           | true       | false      ",
    "Hooks either side | 1               | 1              | 2            | 2           | false      | false      ",
    "Custom later hook | 2               | 2              | 1            | 1           | true       | true       "
  })
  void replacementPreservesContentsAndCallbackOrder(
      int ordinaryBefore,
      int ordinaryAfter,
      int hooksBefore,
      int hooksAfter,
      boolean dispatcher,
      boolean customView) {
    DatadogThreadContextElement original =
        (DatadogThreadContextElement) addDatadogElement(EmptyCoroutineContext.INSTANCE);
    CoroutineContext input = EmptyCoroutineContext.INSTANCE;
    for (int i = 0; i < ordinaryBefore; i++) {
      input = input.plus(new Element());
    }
    for (int i = 0; i < hooksBefore; i++) {
      input = input.plus(new Hook());
    }
    input = input.plus(original);
    for (int i = 0; i < ordinaryAfter; i++) {
      input = input.plus(new Element());
    }
    for (int i = 0; i < hooksAfter; i++) {
      input = input.plus(new Hook());
    }
    if (dispatcher) {
      input = input.plus(new Interceptor());
    }
    if (customView) {
      input = new ContextView(input);
    }
    assertReplacement(input, original);
  }

  @Test
  void replacementPreservesLogicalContextEqualityAndHashCode() {
    CoroutineContext original =
        addDatadogElement(EmptyCoroutineContext.INSTANCE).plus(new Element());
    CoroutineContext replacement = addDatadogElement(original);
    assertNotSame(original, replacement);
    assertEquals(original, replacement);
    assertEquals(original.hashCode(), replacement.hashCode());
  }

  @Test
  void contextWithoutDatadogElementRetainsItsContents() {
    Element ordinary = new Element();
    Hook hook = new Hook();
    CoroutineContext input = ordinary.plus(hook);
    CoroutineContext output = addDatadogElement(input);
    assertSame(ordinary, lookup(output, ordinary));
    assertSame(hook, lookup(output, hook));
    assertEquals(3, keys(output).size());
    assertEquals(ordinary.getKey(), keys(output).get(0));
    assertEquals(hook.getKey(), keys(output).get(1));
  }

  private static void assertReplacement(
      CoroutineContext input, DatadogThreadContextElement original) {
    CoroutineContext output = addDatadogElement(input);
    assertSame(original, lookup(input, original));
    assertNotSame(original, lookup(output, original));
    assertEquals(keys(input).size(), keys(output).size());
    assertEquals(hookKeys(input), hookKeys(output));
    input.fold(
        output,
        (rebuilt, element) -> {
          if (element != original) {
            assertSame(element, lookup(rebuilt, element));
          }
          return rebuilt;
        });
  }

  private static List<CoroutineContext.Key<?>> hookKeys(CoroutineContext context) {
    return context.fold(
        new ArrayList<CoroutineContext.Key<?>>(),
        (keys, element) -> {
          if (element instanceof ThreadContextElement) {
            keys.add(element.getKey());
          }
          return keys;
        });
  }

  @SuppressWarnings("unchecked")
  private static CoroutineContext.Element lookup(
      CoroutineContext context, CoroutineContext.Element element) {
    return context.get((CoroutineContext.Key<CoroutineContext.Element>) element.getKey());
  }

  private static List<CoroutineContext.Key<?>> keys(CoroutineContext context) {
    return context.fold(
        new ArrayList<CoroutineContext.Key<?>>(),
        (keys, element) -> {
          keys.add(element.getKey());
          return keys;
        });
  }

  private static class Element extends AbstractCoroutineContextElement {
    Element() {
      super(new CoroutineContext.Key<Element>() {});
    }
  }

  private static final class Hook extends Element implements ThreadContextElement<Object> {
    @Override
    public Object updateThreadContext(CoroutineContext context) {
      return null;
    }

    @Override
    public void restoreThreadContext(CoroutineContext context, Object state) {}
  }

  private static final class Interceptor extends AbstractCoroutineContextElement
      implements ContinuationInterceptor {
    Interceptor() {
      super(ContinuationInterceptor.Key);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Continuation<T> interceptContinuation(Continuation<? super T> continuation) {
      return (Continuation<T>) continuation;
    }

    @Override
    public void releaseInterceptedContinuation(Continuation<?> continuation) {}
  }

  private static final class ContextView implements CoroutineContext {
    private final CoroutineContext delegate;

    ContextView(CoroutineContext delegate) {
      this.delegate = delegate;
    }

    @Override
    public <E extends CoroutineContext.Element> E get(CoroutineContext.Key<E> key) {
      return delegate.get(key);
    }

    @Override
    public <R> R fold(
        R initial, Function2<? super R, ? super CoroutineContext.Element, ? extends R> operation) {
      return delegate.fold(initial, operation);
    }

    @Override
    public CoroutineContext minusKey(CoroutineContext.Key<?> key) {
      return delegate.minusKey(key);
    }

    @Override
    public CoroutineContext plus(CoroutineContext other) {
      return CoroutineContext.DefaultImpls.plus(this, other);
    }
  }
}
