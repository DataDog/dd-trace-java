package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Guards the production names/constants that {@link ScopeContinuationProbe} relies on reflectively
 * or mirrors. If any of these are renamed/changed in the tracer, these assertions fail loudly
 * instead of the diagnostic silently going dark.
 */
class ScopeContinuationProbeTest {

  @Test
  void cancelledSentinelMatchesProduction() throws Exception {
    assertEquals(Integer.MIN_VALUE >> 1, ScopeContinuationProbe.CANCELLED);

    Class<?> scopeContinuation = Class.forName("datadog.trace.core.scopemanager.ScopeContinuation");
    Field cancelled = scopeContinuation.getDeclaredField("CANCELLED");
    cancelled.setAccessible(true);
    assertEquals(
        cancelled.getInt(null),
        ScopeContinuationProbe.CANCELLED,
        "ScopeContinuationProbe.CANCELLED is out of sync with ScopeContinuation.CANCELLED");
  }

  @Test
  void scopeLifecycleHooksExist() throws Exception {
    Class<?> scope = Class.forName("datadog.trace.core.scopemanager.ContinuableScope");
    assertNotNull(scope.getDeclaredMethod("afterActivated"), "afterActivated() (scope open)");
    assertNotNull(scope.getDeclaredMethod("onProperClose"), "onProperClose() (scope close)");
    assertNotNull(scope.getDeclaredMethod("close"), "close() (wrong-thread check)");
    // source byte read reflectively in the probe
    assertNotNull(findField(scope, "source"), "ContinuableScope.source");

    Class<?> continuing = Class.forName("datadog.trace.core.scopemanager.ContinuingScope");
    assertNotNull(
        continuing.getDeclaredField("continuation"), "ContinuingScope.continuation (scope link)");
  }

  @Test
  void wrongThreadCheckChainExists() throws Exception {
    Class<?> scope = Class.forName("datadog.trace.core.scopemanager.ContinuableScope");
    assertNotNull(findField(scope, "scopeManager"), "ContinuableScope.scopeManager");
    Class<?> manager = Class.forName("datadog.trace.core.scopemanager.ContinuableScopeManager");
    assertNotNull(manager.getDeclaredMethod("scopeStack"), "ContinuableScopeManager.scopeStack()");
    Class<?> stack = Class.forName("datadog.trace.core.scopemanager.ScopeStack");
    assertTrue(hasMethod(stack, "checkTop", 1), "ScopeStack.checkTop(scope)");
  }

  private static Field findField(Class<?> cls, String name) {
    for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
      try {
        return c.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        // keep walking
      }
    }
    return null;
  }

  private static boolean hasMethod(Class<?> cls, String name, int params) {
    for (Method m : cls.getDeclaredMethods()) {
      if (m.getName().equals(name) && m.getParameterCount() == params) {
        return true;
      }
    }
    return false;
  }
}
