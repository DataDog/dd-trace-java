package datadog.context;

import static datadog.context.Context.current;
import static datadog.context.Context.isCaptureEnabled;
import static datadog.context.Context.root;
import static datadog.context.Context.setCaptureEnabled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.tabletest.junit.TableTest;

class ContextCaptureTest extends ContextTestBase {
  @AfterEach
  void resetCapture() {
    setCaptureEnabled(true);
  }

  @Test
  void enabledByDefault() {
    assertTrue(isCaptureEnabled());
  }

  @TableTest({
    "Scenario                  | enabled | captureCurrent",
    "capture active enabled    | true    | true          ",
    "capture other enabled     | true    | false         ",
    "capture active suppressed | false   | true          ",
    "capture other suppressed  | false   | false         "
  })
  void captureHonorsExecutionUnitFlag(boolean enabled, boolean captureCurrent) {
    Context active = root().with(TEST_KEY, "active");
    Context captured = captureCurrent ? active : root().with(TEST_KEY, "other");
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    ContextContinuation noop = root().capture();
    try (ContextScope ignored = active.attach()) {
      listener.assertNewEvents("update:{root}->active");
      setCaptureEnabled(enabled);
      ContextContinuation continuation = captured.capture();
      if (enabled) {
        assertSame(captured, continuation.context());
        listener.assertNewEvents("capture:" + (captureCurrent ? "active" : "other"));
        continuation.release();
        listener.assertNewEvents("release:" + (captureCurrent ? "active" : "other"));
      } else {
        assertSame(noop, continuation);
        assertSame(noop, continuation.hold());
        try (ContextScope scope = continuation.resume()) {
          assertSame(active, current());
        }
        continuation.release();
        listener.assertNoNewEvents();
      }
    }
    assertTrue(isCaptureEnabled());
  }

  @TableTest({
    "Scenario           | enabled | checkpointCurrent",
    "current enabled    | true    | true             ",
    "other enabled      | true    | false            ",
    "current suppressed | false   | true             ",
    "other suppressed   | false   | false            "
  })
  void checkpointSavesFlagsWithoutChangingCurrentState(boolean enabled, boolean checkpointCurrent) {
    Context active = root().with(TEST_KEY, "active");
    Context saved = checkpointCurrent ? active : root().with(TEST_KEY, "other");
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    try (ContextScope ignored = active.attach()) {
      listener.assertNewEvents("update:{root}->active");
      setCaptureEnabled(enabled);
      ContextHandle checkpoint = saved.checkpoint();
      assertSame(active, current());
      assertEquals(enabled, isCaptureEnabled());
      assertSavedFlags(saved, enabled, checkpoint);
      listener.assertNoNewEvents();

      root().swap();
      setCaptureEnabled(!enabled);
      checkpoint.swap();
      assertSame(saved, current());
      assertEquals(enabled, isCaptureEnabled());
    } finally {
      root().swap();
    }
  }

  @TableTest({
    "Scenario              | before | during",
    "enabled throughout    | true   | true  ",
    "restore enabled       | true   | false ",
    "restore suppressed    | false  | true  ",
    "suppressed throughout | false  | false "
  })
  void differentContextResetsCaptureAndRestoresSavedFlags(boolean before, boolean during) {
    Context first = root().with(TEST_KEY, "first");
    Context second = root().with(TEST_KEY, "second");
    try (ContextScope ignored = first.attach()) {
      setCaptureEnabled(before);
      ContextHandle saved = second.swap();
      assertSame(second, current());
      assertTrue(isCaptureEnabled());
      assertSavedFlags(first, before, saved);
      setCaptureEnabled(during);
      ContextHandle displaced = saved.swap();
      assertSame(first, current());
      assertEquals(before, isCaptureEnabled());
      assertSavedFlags(second, during, displaced);
    }
  }

  @TableTest({
    "Scenario              | before | during",
    "enabled throughout    | true   | true  ",
    "retain suppression    | true   | false ",
    "retain enabled        | false  | true  ",
    "suppressed throughout | false  | false "
  })
  void sameContextCheckpointsWithoutResettingOrRestoringFlags(boolean before, boolean during) {
    Context context = root().with(TEST_KEY, "checkpoint");
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    try (ContextScope ignored = context.attach()) {
      listener.assertNewEvents("update:{root}->checkpoint");
      setCaptureEnabled(before);
      ContextHandle checkpoint = current().swap();
      assertSame(context, current());
      assertEquals(before, isCaptureEnabled());
      assertSavedFlags(context, before, checkpoint);
      setCaptureEnabled(during);
      ContextHandle displaced = checkpoint.swap();
      assertSame(context, current());
      assertEquals(during, isCaptureEnabled());
      assertSavedFlags(context, during, displaced);
      listener.assertNoNewEvents();
      // The same checkpoint restores its saved flags once the underlying context changes.
      root().swap();
      checkpoint.swap();
      assertSame(context, current());
      assertEquals(before, isCaptureEnabled());
      listener.assertNewEvents("update:checkpoint->{root}", "update:{root}->checkpoint");
    }
  }

  @Test
  void suppressedRootCanBeCheckpointedAndRestored() {
    setCaptureEnabled(false);
    ContextHandle checkpoint = root().checkpoint();
    assertSame(root(), current());
    assertFalse(isCaptureEnabled());
    Context context = root().with(TEST_KEY, "active");
    context.swap();
    try {
      assertTrue(isCaptureEnabled());
      checkpoint.swap();
      assertSame(root(), current());
      assertFalse(isCaptureEnabled());
    } finally {
      root().swap();
    }
  }

  @Test
  void savedFlagsFollowContextAcrossThreads() {
    Context context = root().with(TEST_KEY, "migrating");
    try (ContextScope ignored = context.attach()) {
      setCaptureEnabled(false);
      ContextHandle checkpoint = current().checkpoint();
      CompletableFuture.runAsync(
              () -> {
                assertSame(root(), current());
                assertTrue(isCaptureEnabled());
                ContextHandle previous = checkpoint.swap();
                try {
                  assertSame(context, current());
                  assertFalse(isCaptureEnabled());
                } finally {
                  previous.swap();
                }
                assertSame(root(), current());
                assertTrue(isCaptureEnabled());
              })
          .join();
      assertSame(context, current());
      assertFalse(isCaptureEnabled());
    }
  }

  private static void assertSavedFlags(Context context, boolean enabled, ContextHandle saved) {
    assertEquals(context.get(TEST_KEY), saved.get(TEST_KEY));
    assertNull(saved.get(ContextKey.named("absent")));
    if (enabled) {
      assertSame(context, saved);
    } else {
      FlaggedContext flagged = assertInstanceOf(FlaggedContext.class, saved);
      assertSame(context, flagged.context);
      assertEquals(1, flagged.flags);
    }
  }

  @Test
  void nestedScopesRestoreFlagsAfterSwap() {
    Context outer = root().with(TEST_KEY, "outer");
    Context inner = root().with(TEST_KEY, "inner");
    try (ContextScope outerScope = outer.attach()) {
      setCaptureEnabled(false);
      try (ContextScope innerScope = inner.attach()) {
        assertFalse(isCaptureEnabled());
        setCaptureEnabled(true);
        ContextHandle previous = root().swap();
        assertTrue(isCaptureEnabled());
        previous.swap();
      }
      assertSame(outer, current());
      assertFalse(isCaptureEnabled());
    }
    assertTrue(isCaptureEnabled());
  }

  @Test
  void sameContextAttachDoesNotRestoreFlag() {
    Context context = root().with(TEST_KEY, "value");
    try (ContextScope outer = context.attach()) {
      try (ContextScope noop = context.attach()) {
        setCaptureEnabled(false);
      }
      assertFalse(isCaptureEnabled());
    }
    assertTrue(isCaptureEnabled());
  }

  @Test
  void existingContinuationCanResumeWhileSuppressed() {
    Context context = root().with(TEST_KEY, "captured");
    ContextContinuation continuation = context.capture();
    setCaptureEnabled(false);
    try (ContextScope ignored = continuation.resume()) {
      assertSame(context, current());
      assertFalse(isCaptureEnabled());
    }
    assertSame(root(), current());
    assertFalse(isCaptureEnabled());
  }

  @TableTest({
    "Scenario           | enabled",
    "restore enabled    | true   ",
    "restore suppressed | false  "
  })
  void resumedScopeRestoresCaptureFlagAfterChange(boolean enabled) {
    Context context = root().with(TEST_KEY, "captured");
    ContextContinuation continuation = context.capture();
    setCaptureEnabled(enabled);
    try (ContextScope ignored = continuation.resume()) {
      assertSame(context, current());
      assertEquals(enabled, isCaptureEnabled());
      setCaptureEnabled(!enabled);
    }
    assertSame(root(), current());
    assertEquals(enabled, isCaptureEnabled());
  }

  @Test
  void outOfOrderClosePreservesCaptureFlagUntilScopesUnwind() {
    Context outer = root().with(TEST_KEY, "outer");
    Context inner = root().with(TEST_KEY, "inner");
    try (ContextScope outerScope = outer.attach()) {
      try (ContextScope innerScope = inner.attach()) {
        setCaptureEnabled(false);
        outerScope.close();
        assertSame(inner, current());
        assertFalse(isCaptureEnabled());
      }
      assertSame(outer, current());
      assertTrue(isCaptureEnabled());
    }
    assertSame(root(), current());
    assertTrue(isCaptureEnabled());
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_21)
  void virtualThreadRetainsSuppressionAcrossParkAndSwap() throws Exception {
    Context context = root().with(TEST_KEY, "virtual");
    CompletableFuture<Void> result = new CompletableFuture<>();
    try (ContextScope parentScope = context.attach()) {
      setCaptureEnabled(false);
      Runnable task =
          () -> {
            try {
              assertTrue(isCaptureEnabled());
              try (ContextScope scope = context.attach()) {
                setCaptureEnabled(false);
                for (int i = 0; i < 5; i++) {
                  ContextHandle previous = root().swap();
                  LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                  previous.swap();
                  assertSame(context, current());
                  assertFalse(isCaptureEnabled());
                }
                try (ContextScope inner = context.with(TEST_KEY, "nested").attach()) {
                  setCaptureEnabled(true);
                }
                assertFalse(isCaptureEnabled());
              }
              assertTrue(isCaptureEnabled());
              result.complete(null);
            } catch (Throwable failure) {
              result.completeExceptionally(failure);
            }
          };
      Thread thread =
          (Thread) Thread.class.getMethod("startVirtualThread", Runnable.class).invoke(null, task);
      result.get(10, TimeUnit.SECONDS);
      thread.join(TimeUnit.SECONDS.toMillis(10));
      assertFalse(thread.isAlive());
      assertFalse(isCaptureEnabled());
    }
    assertTrue(isCaptureEnabled());
  }
}
