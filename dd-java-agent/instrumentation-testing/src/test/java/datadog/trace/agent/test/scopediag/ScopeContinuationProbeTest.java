package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.context.ContextScope;
import datadog.trace.api.DDTraceId;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/** Verifies the tracer internals used by {@link ScopeContinuationProbe}. */
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
  void continuationHooksExist() throws Exception {
    Class<?> scopeContinuation = Class.forName("datadog.trace.core.scopemanager.ScopeContinuation");
    assertMethod(scopeContinuation, "register", scopeContinuation);
    assertMethod(scopeContinuation, "resume", ContextScope.class);
    assertMethod(scopeContinuation, "release", void.class);
    assertMethod(scopeContinuation, "cancelFromContinuedScopeClose", void.class);
    assertField(scopeContinuation, "count", int.class);
    assertField(scopeContinuation, "source", byte.class);
  }

  @Test
  void rootWrittenHookExists() throws Exception {
    Class<?> pendingTrace = Class.forName("datadog.trace.core.PendingTrace");
    assertMethod(pendingTrace, "write", int.class, boolean.class);
    assertField(pendingTrace, "rootSpanWritten", boolean.class);
    assertField(pendingTrace, "traceId", DDTraceId.class);
  }

  @Test
  void scopeLifecycleHooksExist() throws Exception {
    Class<?> scope = Class.forName("datadog.trace.core.scopemanager.ContinuableScope");
    Class<?> stack = Class.forName("datadog.trace.core.scopemanager.ScopeStack");
    assertMethod(stack, "push", void.class, scope);
    assertMethod(scope, "onProperClose", void.class);
    assertMethod(scope, "close", void.class);
    assertField(scope, "source", byte.class);
    Class<?> continuing = Class.forName("datadog.trace.core.scopemanager.ContinuingScope");
    Class<?> continuation = Class.forName("datadog.trace.core.scopemanager.ScopeContinuation");
    assertField(continuing, "continuation", continuation);
    Class<?> manager = Class.forName("datadog.trace.core.scopemanager.ContinuableScopeManager");
    assertMethod(manager, "scheduleRootIterationScopeCleanup", void.class, stack, scope);
  }

  @Test
  void wrongThreadCheckChainExists() throws Exception {
    Class<?> scope = Class.forName("datadog.trace.core.scopemanager.ContinuableScope");

    Class<?> manager = Class.forName("datadog.trace.core.scopemanager.ContinuableScopeManager");
    assertField(scope, "scopeManager", manager);
    Class<?> stack = Class.forName("datadog.trace.core.scopemanager.ScopeStack");
    assertMethod(manager, "scopeStack", stack);
    assertMethod(stack, "checkTop", boolean.class, scope);
  }

  private static Field findField(Class<?> cls, String name) {
    for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
      try {
        return c.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
      }
    }
    return null;
  }

  private static void assertField(Class<?> owner, String name, Class<?> type) {
    Field field = findField(owner, name);
    assertNotNull(field, owner.getName() + "." + name);
    assertEquals(type, field.getType(), field.toString());
    assertFalse(Modifier.isStatic(field.getModifiers()), field.toString());
    field.setAccessible(true);
  }

  private static void assertMethod(
      Class<?> owner, String name, Class<?> returnType, Class<?>... arguments) throws Exception {
    Method method = owner.getDeclaredMethod(name, arguments);
    assertEquals(returnType, method.getReturnType(), method.toString());
    assertFalse(Modifier.isStatic(method.getModifiers()), method.toString());
    method.setAccessible(true);
  }
}
