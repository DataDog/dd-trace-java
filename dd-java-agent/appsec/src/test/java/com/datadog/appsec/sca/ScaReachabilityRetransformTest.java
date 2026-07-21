package com.datadog.appsec.sca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.StringReader;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

  /** Second dummy class, distinct from {@link Target}, used to simulate a mixed batch. */
  public static class Other {
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
        "the failing batch of 2 must be bisected into 2 singleton batches for the next"
            + " heartbeat retry");
  }

  @Test
  void performPendingRetransforms_dropsSingletonBatchImmediatelyOnFailure() throws Exception {
    // retransformClasses() is atomic for the whole array: an unrelated, permanently-failing class
    // (this test simulates it with a RuntimeException on every attempt) must not be re-queued
    // forever, or its Class<?> (and its ClassLoader) is retained indefinitely — the Metaspace leak
    // from APPSEC-69201. Once a batch is down to a single class, a failure is treated as proof the
    // class itself is the cause and it is dropped on the spot, with no retry budget.
    Instrumentation instr = mock(Instrumentation.class);
    when(instr.isModifiableClass(Target.class)).thenReturn(true);
    doThrow(new RuntimeException("retransform failed")).when(instr).retransformClasses(any());

    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader("{\"version\":1,\"entries\":[]}"));
    ScaReachabilityTransformer t = new ScaReachabilityTransformer(db, instr);
    t.pendingRetransform.add(Collections.singletonList(Target.class));

    t.performPendingRetransforms();

    assertTrue(
        t.pendingRetransform.isEmpty(), "class must be dropped (not re-queued) after one failure");
  }

  @Test
  void performPendingRetransforms_doesNotRequeueSingletonBatchOnSuccess() throws Exception {
    Instrumentation instr = mock(Instrumentation.class);
    when(instr.isModifiableClass(Target.class)).thenReturn(true);

    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader("{\"version\":1,\"entries\":[]}"));
    ScaReachabilityTransformer t = new ScaReachabilityTransformer(db, instr);

    t.pendingRetransform.add(Collections.singletonList(Target.class));
    t.performPendingRetransforms();

    verify(instr).retransformClasses(Target.class);
    assertTrue(t.pendingRetransform.isEmpty(), "class must not be re-queued after success");
  }

  @Test
  void performPendingRetransforms_isolatesHealthyClassFromPermanentlyFailingBatchMateViaBisection()
      throws Exception {
    // A batch containing one permanently-failing class and one otherwise-healthy class fails once
    // as a pair. Rather than tying both classes together, the batch is immediately bisected into
    // two singleton batches, deferred to the next heartbeat. On the next heartbeat the healthy
    // class retransforms successfully on its own, isolated from its unrelated, permanently-failing
    // batch-mate — which is dropped on the spot since it fails again alone.
    Class<?> poison = Target.class;
    Class<?> healthy = Other.class;

    Instrumentation instr = mock(Instrumentation.class);
    when(instr.isModifiableClass(poison)).thenReturn(true);
    when(instr.isModifiableClass(healthy)).thenReturn(true);
    doAnswer(
            invocation -> {
              // Mockito flattens the varargs invocation, so getArguments() yields the individual
              // Class<?> elements rather than the backing array.
              for (Object arg : invocation.getArguments()) {
                if (arg == poison) {
                  throw new RuntimeException("retransform failed");
                }
              }
              return null;
            })
        .when(instr)
        .retransformClasses(any());

    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader("{\"version\":1,\"entries\":[]}"));
    ScaReachabilityTransformer t = new ScaReachabilityTransformer(db, instr);
    t.pendingRetransform.add(new ArrayList<>(Arrays.asList(poison, healthy)));

    t.performPendingRetransforms(); // batch of 2 fails, bisects into [poison] and [healthy]
    t.performPendingRetransforms(); // [healthy] succeeds alone; [poison] fails alone and is dropped

    verify(instr).retransformClasses(healthy);
    assertTrue(
        t.pendingRetransform.isEmpty(),
        "healthy class must be successfully retransformed and the poison class dropped, leaving"
            + " nothing queued");
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
