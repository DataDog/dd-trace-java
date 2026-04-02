package testdog.trace.instrumentation.java.lang.jdk21;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Trace;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Test context tracking through {@code VirtualThread} lifecycle - park/unpark (remount) cycles. */
public class VirtualThreadLifeCycleTest extends AbstractInstrumentationTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @DisplayName("test context restored after virtual thread remounts")
  @Test
  void testContextRestoredAfterVirtualThreadRemount() {
    int remountCount = 5;
    String[] spanId = new String[1];
    String[] spanIdBeforeUnmount = new String[1];
    String[] spanIdsAfterRemount = new String[remountCount];

    new Runnable() {
      @Override
      @Trace(operationName = "parent")
      public void run() {
        spanId[0] = GlobalTracer.get().getSpanId();

        Thread thread =
            Thread.startVirtualThread(
                () -> {
                  spanIdBeforeUnmount[0] = GlobalTracer.get().getSpanId();
                  for (int remount = 0; remount < remountCount; remount++) {
                    tryUnmount();
                    spanIdsAfterRemount[remount] = GlobalTracer.get().getSpanId();
                  }
                });
        try {
          thread.join(TIMEOUT);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }.run();

    assertEquals(
        spanId[0],
        spanIdBeforeUnmount[0],
        "context should be inherited from the parent execution unit");
    for (int i = 0; i < remountCount; i++) {
      assertEquals(
          spanId[0],
          spanIdsAfterRemount[i],
          "context should be restored after virtual thread remounts");
    }

    assertTraces(trace(span().root().operationName("parent")));
  }

  @DisplayName("test context restored as implicit parent span after remount")
  @Test
  void testContextRestoredAsImplicitParentSpanAfterRemount() {
    new Runnable() {
      @Override
      @Trace(operationName = "parent")
      public void run() {
        Thread thread =
            Thread.startVirtualThread(
                () -> {
                  tryUnmount();
                  // Runnable to create child span, not async related
                  new Runnable() {
                    @Override
                    @Trace(operationName = "child")
                    public void run() {}
                  }.run();
                });
        try {
          thread.join(TIMEOUT);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        blockUntilChildSpansFinished(1);
      }
    }.run();

    assertTraces(
        trace(
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("child")));
  }

  @DisplayName("test concurrent virtual threads with remount")
  @Test
  void testConcurrentVirtualThreadsWithRemount() {
    int threadCount = 5;
    String[] spanId = new String[1];
    String[] spanIdsAfterRemount = new String[threadCount];

    new Runnable() {
      @Override
      @Trace(operationName = "parent")
      public void run() {
        spanId[0] = CorrelationIdentifier.getSpanId();

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
          int index = i;
          threads.add(
              Thread.startVirtualThread(
                  () -> {
                    tryUnmount();
                    spanIdsAfterRemount[index] = CorrelationIdentifier.getSpanId();
                  }));
        }

        for (Thread thread : threads) {
          try {
            thread.join(TIMEOUT);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
      }
    }.run();

    for (int i = 0; i < threadCount; i++) {
      assertEquals(
          spanId[0],
          spanIdsAfterRemount[i],
          "context should be restored after virtual thread #" + i + "remounts");
    }

    assertTraces(trace(span().root().operationName("parent")));
  }

  @DisplayName("test no context virtual thread remount")
  @Test
  void testNoContextVirtualThreadRemount() throws InterruptedException {
    AtomicReference<String> spanIdBeforeUnmount = new AtomicReference<>();
    AtomicReference<String> spanIdAfterRemount = new AtomicReference<>();

    Thread.startVirtualThread(
            () -> {
              spanIdBeforeUnmount.set(CorrelationIdentifier.getSpanId());
              tryUnmount();
              spanIdAfterRemount.set(CorrelationIdentifier.getSpanId());
            })
        .join(TIMEOUT);

    assertEquals(
        "0", spanIdBeforeUnmount.get(), "there should be no active context before unmount");
    assertEquals("0", spanIdAfterRemount.get(), "there should be no active context after remount");
  }

  @DisplayName("test context ordering with child span across unmount/remount")
  @Test
  void testContextOrderingWithChildSpanAcrossRemount() throws InterruptedException {
    String[] parentSpanId = new String[1];
    String[] beforeChild = new String[1];
    String[] insideChildBeforeUnmount = new String[1];
    String[] insideChildAfterRemount = new String[1];
    String[] afterChild = new String[1];

    new Runnable() {
      @Override
      @Trace(operationName = "parent")
      public void run() {
        parentSpanId[0] = GlobalTracer.get().getSpanId();

        Thread thread =
            Thread.startVirtualThread(
                () -> {
                  beforeChild[0] = GlobalTracer.get().getSpanId();
                  childWork(insideChildBeforeUnmount, insideChildAfterRemount);
                  afterChild[0] = GlobalTracer.get().getSpanId();
                });
        try {
          thread.join();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        blockUntilChildSpansFinished(1);
      }
    }.run();

    // Verify context ordering at each checkpoint
    assertEquals(parentSpanId[0], beforeChild[0], "parent should be active before child span");
    assertNotEquals("0", insideChildBeforeUnmount[0], "child should be active before unmount");
    assertNotEquals(
        parentSpanId[0], insideChildBeforeUnmount[0], "active span should be child, not parent");
    assertEquals(
        insideChildBeforeUnmount[0],
        insideChildAfterRemount[0],
        "child should still be active after remount (no out-of-order scope close)");
    assertEquals(parentSpanId[0], afterChild[0], "parent should be active after child span closes");

    // Verify trace structure
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("child")));
  }

  @Trace(operationName = "child")
  private static void childWork(String[] beforeUnmount, String[] afterRemount) {
    beforeUnmount[0] = GlobalTracer.get().getSpanId();
    tryUnmount();
    afterRemount[0] = GlobalTracer.get().getSpanId();
  }

  private static void tryUnmount() {
    try {
      // Multiple sleeps to expect triggering repeated park/unpark cycles.
      // This is not guaranteed to work, but there is no API to force mount/unmount.
      for (int i = 0; i < 5; i++) {
        Thread.sleep(10);
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
