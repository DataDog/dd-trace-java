package datadog.context;

import static datadog.context.Context.current;
import static datadog.context.Context.root;
import static java.util.Collections.singletonList;
import static java.util.Collections.synchronizedList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.annotation.ParametersAreNonnullByDefault;
import org.junit.jupiter.api.Test;

@ParametersAreNonnullByDefault
class ContextContinuationTest extends ContextTestBase {
  private static final ContextKey<String> CONTINUATION_KEY = ContextKey.named("continuation-key");

  @Test
  void testCaptureRootContextIsNoop() {
    ContextContinuation continuation = root().capture();
    assertEquals(root(), continuation.context());
    assertSame(continuation, continuation.hold()); // hold is a no-op, returns self
    try (ContextScope scope = continuation.resume()) {
      assertEquals(root(), current()); // nothing changes for root
    }
    assertEquals(root(), current());
    continuation.release(); // no-op
  }

  @Test
  void testCaptureStoresContext() {
    Context context = root().with(CONTINUATION_KEY, "captured");
    try (ContextScope scope = context.attach()) {
      ContextContinuation continuation = context.capture();
      assertEquals(context, continuation.context());
      continuation.release();
    }
  }

  @Test
  void testCaptureFiresOnCaptureEvent() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    Context context = root().with(CONTINUATION_KEY, "value");
    try (ContextScope scope = context.attach()) {
      ContextContinuation continuation =
          context.capture(); // capture while active (recommended pattern)
      listener.assertNewEvents("attach", "capture");
      continuation.release();
    }
    listener.assertNewEvents("release", "detach");
  }

  @Test
  void testResumeAttachesContextAndRestoresPreviousOnClose() {
    Context context = root().with(CONTINUATION_KEY, "value");
    ContextContinuation continuation;
    try (ContextScope scope = context.attach()) {
      continuation = context.capture(); // capture while active (recommended pattern)
    }
    // original scope is closed; resume the continuation here (same or different thread)
    try (ContextScope scope = continuation.resume()) {
      assertEquals(context, current());
      assertEquals(context, scope.context());
    }
    assertEquals(root(), current());
  }

  @Test
  void testResumeAndScopeCloseFiresLifecycleEvents() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    Context context = root().with(CONTINUATION_KEY, "value");
    ContextContinuation continuation;
    try (ContextScope scope = context.attach()) {
      continuation = context.capture(); // capture while active
    }
    listener.assertNewEvents("attach", "capture", "detach");
    try (ContextScope scope = continuation.resume()) {
      listener.assertNewEvents("attach");
    }
    // release fires before detach (continuation is released first inside ContextScopeImpl.close)
    listener.assertNewEvents("release", "detach");
  }

  @Test
  void testHoldPreventsAutoReleaseOnScopeClose() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    Context context = root().with(CONTINUATION_KEY, "value");
    ContextContinuation continuation;
    try (ContextScope scope = context.attach()) {
      continuation = context.capture(); // capture while active
      continuation.hold();
    }
    try (ContextScope scope = continuation.resume()) {
      assertEquals(context, current());
    }
    assertEquals(root(), current());
    // release should not fire while hold is active
    listener.assertNewEvents("attach", "capture", "detach", "attach", "detach");
    continuation.release();
    listener.assertNewEvents("release");
  }

  @Test
  void testExplicitReleaseWithoutResumeFiresReleaseEvent() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    Context context = root().with(CONTINUATION_KEY, "value");
    ContextContinuation continuation;
    try (ContextScope scope = context.attach()) {
      continuation = context.capture(); // capture while active
    }
    listener.assertNewEvents("attach", "capture", "detach");
    continuation.release();
    listener.assertNewEvents("release");
  }

  @Test
  void testResumeAfterReleaseIsNoop() {
    Context context = root().with(CONTINUATION_KEY, "value");
    ContextContinuation continuation;
    try (ContextScope scope = context.attach()) {
      continuation = context.capture(); // capture while active
    }
    continuation.release();
    // Resuming a released continuation should not attach the context
    try (ContextScope scope = continuation.resume()) {
      assertEquals(root(), current());
    }
    assertEquals(root(), current());
  }

  @Test
  void testResumeOnDifferentThread() {
    Context context = root().with(CONTINUATION_KEY, "value");
    ContextContinuation continuation;
    try (ContextScope scope = context.attach()) {
      continuation = context.capture(); // capture while active (recommended pattern)
    }
    // original scope is closed; resume the context on another thread
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> future =
          executor.submit(
              () -> {
                assertEquals(root(), current()); // thread starts with root context
                try (ContextScope scope = continuation.resume()) {
                  assertEquals(context, current());
                }
                assertEquals(root(), current()); // restored after scope close
              });
      assertDoesNotThrow(() -> future.get());
    } finally {
      executor.shutdown();
    }
  }

  @Test
  void testMultipleResumesReleaseAfterLastScopeCloses() throws InterruptedException {
    List<String> events = synchronizedList(new ArrayList<>());
    ContextManager.register(
        new ContextListener() {
          @Override
          public void onRelease(Context c) {
            events.add("release");
          }
        });
    Context context = root().with(CONTINUATION_KEY, "value");
    ContextContinuation continuation;
    try (ContextScope scope = context.attach()) {
      continuation = context.capture(); // capture while active
    }
    CountDownLatch bothResumed = new CountDownLatch(2);
    CountDownLatch closeFirst = new CountDownLatch(1);
    CountDownLatch firstClosed = new CountDownLatch(1);
    CountDownLatch closeSecond = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> f1 =
          executor.submit(
              () -> {
                try (ContextScope scope = continuation.resume()) {
                  bothResumed.countDown();
                  closeFirst.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  firstClosed.countDown();
                }
              });
      Future<?> f2 =
          executor.submit(
              () -> {
                try (ContextScope scope = continuation.resume()) {
                  bothResumed.countDown();
                  closeSecond.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });
      bothResumed.await();
      assertTrue(events.isEmpty(), "release should not fire while scopes are open");
      closeFirst.countDown();
      firstClosed.await(); // wait for f1's scope to fully close
      assertTrue(events.isEmpty(), "release should not fire after first scope closes");
      closeSecond.countDown();
      assertDoesNotThrow(() -> f1.get());
      assertDoesNotThrow(() -> f2.get());
      assertEquals(singletonList("release"), events);
    } finally {
      executor.shutdown();
    }
  }

  @Test
  void testSameContextResumeReleasesImmediately() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    Context context = root().with(CONTINUATION_KEY, "value");
    try (ContextScope outer = context.attach()) {
      // Context is already current; resume is a noop and continuation is released immediately
      ContextContinuation continuation = context.capture();
      try (ContextScope noop = continuation.resume()) {
        assertEquals(context, current());
        listener.assertNewEvents("attach", "capture", "release"); // released synchronously
      }
      assertEquals(context, current()); // outer scope still holds context
    }
    listener.assertNewEvents("detach");
  }

  @Test
  void testOutOfOrderScopeCloseReleasesImmediately() {
    // Recommended pattern: attach C, capture, close original scope
    Context contextC = root().with(CONTINUATION_KEY, "C");
    ContextContinuation continuation;
    try (ContextScope scope = contextC.attach()) {
      continuation = contextC.capture();
    }

    TrackingListener listener = keyedTrackingListener(CONTINUATION_KEY);
    ContextManager.register(listener);

    Context contextD = root().with(CONTINUATION_KEY, "D");
    try (ContextScope scopeR = continuation.resume()) {
      assertEquals(contextC, current());
      try (ContextScope scopeD = contextD.attach()) { // attaching D fires detach:C, attach:D
        assertEquals(contextD, current());

        // close the resume scope out-of-order while D is still nested on top;
        // release fires immediately, but detach:C does not (C is not current)
        scopeR.close();
        listener.assertNewEvents("attach:C", "detach:C", "attach:D", "release:C");
        assertEquals(contextD, current()); // D is still current
      } // scopeD closes here: unwind D normally, restores C
      listener.assertNewEvents("detach:D", "attach:C");
    } // try-with-resources closes scopeR again; no second release, C unwinds to root

    assertEquals(root(), current());
    listener.assertNewEvents("detach:C");
  }

  @Test
  void testHoldWithOutOfOrderScopeCloseFiresReleaseOnExplicitRelease() {
    // Regression test: hold() + out-of-order close must not corrupt the count,
    // which would cause release() to silently no-op and lose the release event.
    Context contextC = root().with(CONTINUATION_KEY, "C");
    ContextContinuation continuation;
    try (ContextScope scope = contextC.attach()) {
      continuation = contextC.capture();
      continuation.hold();
    }

    TrackingListener listener = keyedTrackingListener(CONTINUATION_KEY);
    ContextManager.register(listener);

    Context contextD = root().with(CONTINUATION_KEY, "D");
    try (ContextScope scopeR = continuation.resume()) {
      assertEquals(contextC, current());
      try (ContextScope scopeD = contextD.attach()) { // detach:C, attach:D
        assertEquals(contextD, current());

        scopeR.close(); // out-of-order close while D is still on top; hold prevents auto-release
        listener.assertNewEvents("attach:C", "detach:C", "attach:D");
        assertEquals(contextD, current());
      } // scopeD closes here: unwind D, restores C
    } // TWR closes scopeR again (now in-order); detach:C, no release yet (hold is active)

    assertEquals(root(), current());
    listener.assertNewEvents("detach:D", "attach:C", "detach:C");

    continuation.release(); // explicit release must fire release:C
    listener.assertNewEvents("release:C");
  }

  @Test
  void testMultipleHoldCallsAreIdempotent() {
    // Calling hold() more than once should not require more than one explicit release().
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    Context context = root().with(CONTINUATION_KEY, "value");
    ContextContinuation continuation;
    try (ContextScope scope = context.attach()) {
      continuation = context.capture();
      continuation.hold();
      continuation.hold(); // second hold must be a no-op
    }
    // One explicit release() is enough — no extra releases needed for the second hold().
    continuation.release();
    listener.assertNewEvents("attach", "capture", "detach", "release");
    continuation.release(); // still idempotent after the final release
    listener.assertNoNewEvents();
  }

  @Test
  void testHoldAfterReleaseIsIgnored() {
    // hold() on an already-released continuation must not resurrect it.
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    Context context = root().with(CONTINUATION_KEY, "value");
    ContextContinuation continuation;
    try (ContextScope scope = context.attach()) {
      continuation = context.capture();
    }
    continuation.release();
    listener.assertNewEvents("attach", "capture", "detach", "release");
    continuation.hold(); // must be silently ignored
    // resume() after release is already a noop, even with the spurious hold()
    try (ContextScope scope = continuation.resume()) {
      assertEquals(root(), current());
    }
    continuation.release(); // must not fire a second release event
    listener.assertNoNewEvents();
  }

  @Test
  void testHoldAllowsMultipleReleaseCalls() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    Context context = root().with(CONTINUATION_KEY, "value");
    ContextContinuation continuation;
    try (ContextScope scope = context.attach()) {
      continuation = context.capture(); // capture while active
      continuation.hold();
    }
    continuation.release();
    listener.assertNewEvents("attach", "capture", "detach", "release");
    continuation.release(); // second release is a no-op
    listener.assertNoNewEvents();
  }
}
