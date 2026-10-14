package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.instrumentation.kotlin.coroutines.DatadogThreadContextElement.addDatadogElement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import kotlin.coroutines.AbstractCoroutineContextElement;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.ContinuationInterceptor;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.ThreadContextElement;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/** Context replacement checks shared by the oldest and latest dependency suites. */
public abstract class AbstractDatadogThreadContextElementTest {
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
    assertEquals(keys(input), keys(output));
    input.fold(
        output,
        (rebuilt, element) -> {
          if (element != original) {
            assertSame(element, lookup(rebuilt, element));
          }
          return rebuilt;
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
