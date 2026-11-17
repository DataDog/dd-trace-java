package datadog.trace.core.monitor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.core.DDSpan;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class CompositeHealthMetricsTest {
  @Test
  void startAndCloseReachEveryDelegateThenRethrowTheFirstFailure() {
    HealthMetrics failing = mock(HealthMetrics.class);
    HealthMetrics next = mock(HealthMetrics.class);
    IllegalStateException startFailure = new IllegalStateException("start");
    IllegalStateException closeFailure = new IllegalStateException("close");
    doThrow(startFailure).when(failing).start();
    doThrow(closeFailure).when(failing).close();
    CompositeHealthMetrics composite = new CompositeHealthMetrics(failing, next);

    assertSame(startFailure, assertThrows(IllegalStateException.class, composite::start));
    assertSame(closeFailure, assertThrows(IllegalStateException.class, composite::close));

    verify(next).start();
    verify(next).close();
  }

  @Test
  void laterFailuresAreSuppressedOnTheFirst() {
    HealthMetrics first = mock(HealthMetrics.class);
    HealthMetrics second = mock(HealthMetrics.class);
    IllegalStateException firstFailure = new IllegalStateException("first");
    IllegalStateException secondFailure = new IllegalStateException("second");
    doThrow(firstFailure).when(first).close();
    doThrow(secondFailure).when(second).close();
    CompositeHealthMetrics composite = new CompositeHealthMetrics(first, second);

    IllegalStateException thrown = assertThrows(IllegalStateException.class, composite::close);

    assertSame(firstFailure, thrown);
    assertArrayEquals(new Throwable[] {secondFailure}, thrown.getSuppressed());
  }

  @Test
  void forwardsCallbacksToEveryDelegateInOrder() {
    HealthMetrics first = mock(HealthMetrics.class);
    HealthMetrics second = mock(HealthMetrics.class);
    DDSpan span = mock(DDSpan.class);
    CompositeHealthMetrics composite = new CompositeHealthMetrics(first, second);

    composite.start();
    composite.onCreateSpan(span);
    composite.onFinishSpan();
    composite.onFlush(true);
    composite.onSerialize(42);
    composite.close();

    InOrder inOrder = inOrder(first, second);
    inOrder.verify(first).start();
    inOrder.verify(second).start();
    inOrder.verify(first).onCreateSpan(span);
    inOrder.verify(second).onCreateSpan(span);
    inOrder.verify(first).onFinishSpan();
    inOrder.verify(second).onFinishSpan();
    inOrder.verify(first).onFlush(true);
    inOrder.verify(second).onFlush(true);
    inOrder.verify(first).onSerialize(42);
    inOrder.verify(second).onSerialize(42);
    inOrder.verify(first).close();
    inOrder.verify(second).close();
    verifyNoMoreInteractions(first, second);
  }

  @Test
  void summaryJoinsNonEmptyDelegateSummaries() {
    HealthMetrics first = mock(HealthMetrics.class);
    HealthMetrics second = mock(HealthMetrics.class);
    HealthMetrics third = mock(HealthMetrics.class);
    when(first.summary()).thenReturn("first");
    when(second.summary()).thenReturn("");
    when(third.summary()).thenReturn("third");

    assertEquals("first\nthird", new CompositeHealthMetrics(first, second, third).summary());
  }

  /** A callback added to {@link HealthMetrics} must also be forwarded by the composite. */
  @Test
  void overridesEveryOverridableHealthMetricsMethod() {
    List<String> missing = new ArrayList<>();
    for (Method method : HealthMetrics.class.getDeclaredMethods()) {
      int modifiers = method.getModifiers();
      if (!Modifier.isPublic(modifiers)
          || Modifier.isStatic(modifiers)
          || Modifier.isFinal(modifiers)
          || method.isSynthetic()) {
        continue;
      }
      try {
        CompositeHealthMetrics.class.getDeclaredMethod(
            method.getName(), method.getParameterTypes());
      } catch (NoSuchMethodException e) {
        missing.add(method.toString());
      }
    }
    assertTrue(missing.isEmpty(), "CompositeHealthMetrics does not forward: " + missing);
  }

  /**
   * Each forwarding method must call the same method, with the same arguments, on every delegate.
   */
  @Test
  void forwardsEveryOverridableMethodToEveryDelegate() throws Exception {
    HealthMetrics first = mock(HealthMetrics.class);
    HealthMetrics second = mock(HealthMetrics.class);
    CompositeHealthMetrics composite = new CompositeHealthMetrics(first, second);
    List<Method> methods = overridableMethods();

    for (Method method : methods) {
      method.invoke(composite, defaultArguments(method));
    }

    for (Method method : methods) {
      Object[] arguments = defaultArguments(method);
      method.invoke(verify(first), arguments);
      method.invoke(verify(second), arguments);
    }
  }

  private static List<Method> overridableMethods() {
    List<Method> methods = new ArrayList<>();
    for (Method method : HealthMetrics.class.getDeclaredMethods()) {
      int modifiers = method.getModifiers();
      if (Modifier.isPublic(modifiers)
          && !Modifier.isStatic(modifiers)
          && !Modifier.isFinal(modifiers)
          && !method.isSynthetic()) {
        methods.add(method);
      }
    }
    return methods;
  }

  /** Zero, false or null for each parameter, so the same arguments can be verified afterwards. */
  private static Object[] defaultArguments(Method method) {
    Class<?>[] types = method.getParameterTypes();
    Object[] arguments = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      Class<?> type = types[i];
      if (type == boolean.class) {
        arguments[i] = false;
      } else if (type == int.class) {
        arguments[i] = 0;
      } else if (type == long.class) {
        arguments[i] = 0L;
      } else if (type.isPrimitive()) {
        throw new IllegalArgumentException("Unsupported parameter type " + type + " in " + method);
      }
    }
    return arguments;
  }

  @Test
  void emptyCompositeIsNoOp() {
    CompositeHealthMetrics composite = new CompositeHealthMetrics();
    assertDoesNotThrow(() -> {
      composite.start();
      composite.onCreateSpan(null);
      composite.close();
    });
    assertEquals("", composite.summary());
  }
}
