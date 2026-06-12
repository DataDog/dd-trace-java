package com.datadog.appsec.sca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.StringReader;
import java.lang.instrument.Instrumentation;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link ScaReachabilityTransformer#performPendingRetransforms()}.
 *
 * <p>Spring Boot fat JARs create multiple {@code LaunchedURLClassLoader} instances, each loading
 * their own copy of the same vulnerable class. The name-based retransform path must retransform ALL
 * classloader instances, not just the first one found.
 *
 * <p>The bug: {@code pendingRetransformNames.remove(name)} inside the loop returned {@code true}
 * only for the first matching class, so subsequent instances were silently skipped. The fix uses
 * {@code contains(name)} inside the loop and {@code removeAll(matched)} after, collecting every
 * class before any removal.
 */
class ScaReachabilityRetransformTest {

  /** Dummy class used as the retransform target in tests. */
  public static class Target {
    public void method() {}
  }

  @Test
  void performPendingRetransforms_retransformsAllMatchingInstances() throws Exception {
    // Returning the same Class<?> twice in getAllLoadedClasses() simulates two classloader
    // instances holding the same vulnerable class. With the old remove()-inside-loop approach
    // only the first entry was retransformed; with contains()+removeAll() both are collected.
    String internalName = Target.class.getName().replace('.', '/');

    Instrumentation instr = mock(Instrumentation.class);
    when(instr.getAllLoadedClasses()).thenReturn(new Class<?>[] {Target.class, Target.class});
    when(instr.isModifiableClass(Target.class)).thenReturn(true);

    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader("{\"version\":1,\"entries\":[]}"));
    ScaReachabilityTransformer t = new ScaReachabilityTransformer(db, instr);
    t.pendingRetransformNames.add(internalName);

    t.performPendingRetransforms();

    // Both entries must reach retransformClasses: with the old remove() approach only the first
    // matched (length 1). With contains()+removeAll() both are collected (length 2).
    // Mockito expands varargs as individual arguments, so verify with two explicit entries.
    verify(instr).retransformClasses(Target.class, Target.class);
    assertTrue(
        t.pendingRetransformNames.isEmpty(),
        "internal name must be removed from the pending set after retransform");
  }

  @Test
  void performPendingRetransforms_requeuesOnRetransformFailure() throws Exception {
    String internalName = Target.class.getName().replace('.', '/');

    Instrumentation instr = mock(Instrumentation.class);
    when(instr.getAllLoadedClasses()).thenReturn(new Class<?>[] {Target.class, Target.class});
    when(instr.isModifiableClass(Target.class)).thenReturn(true);
    doThrow(new RuntimeException("retransform failed")).when(instr).retransformClasses(any());

    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader("{\"version\":1,\"entries\":[]}"));
    ScaReachabilityTransformer t = new ScaReachabilityTransformer(db, instr);
    t.pendingRetransformNames.add(internalName);

    t.performPendingRetransforms();

    assertEquals(
        2,
        t.pendingRetransform.size(),
        "both classes must be re-queued in pendingRetransform for the next heartbeat retry");
  }

  @Test
  void performPendingRetransforms_skipsNonModifiableClasses() throws Exception {
    // Non-modifiable classes (e.g. JDK classes, primitive wrappers) must be silently discarded
    // from the pending set and never passed to retransformClasses. Without this guard they would
    // loop forever: retransformClasses rejects them, the catch re-queues them, next heartbeat
    // tries again, ad infinitum.
    String internalName = Target.class.getName().replace('.', '/');

    Instrumentation instr = mock(Instrumentation.class);
    when(instr.getAllLoadedClasses()).thenReturn(new Class<?>[] {Target.class});
    when(instr.isModifiableClass(Target.class)).thenReturn(false);

    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader("{\"version\":1,\"entries\":[]}"));
    ScaReachabilityTransformer t = new ScaReachabilityTransformer(db, instr);
    t.pendingRetransformNames.add(internalName);

    t.performPendingRetransforms();

    assertTrue(
        t.pendingRetransformNames.isEmpty(),
        "non-modifiable class must be removed from pendingRetransformNames");
    assertTrue(
        t.pendingRetransform.isEmpty(),
        "non-modifiable class must not be re-queued in pendingRetransform");
  }
}
