package testdog.trace.instrumentation.java.lang.jdk21;

import static datadog.context.Context.isCaptureEnabled;
import static datadog.context.Context.setCaptureEnabled;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.Trace;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/** Test the {@code VirtualThread} and {@code Thread.Builder} API. */
public class VirtualThreadApiInstrumentationTest extends AbstractInstrumentationTest {
  @TableTest({
    "Scenario                            | parentCaptureEnabled",
    "Child suppression stays local       | true                ",
    "Parent suppression is not inherited | false               "
  })
  void captureStartsEnabledAndRetainsLocalSuppressionAcrossRemounts(boolean parentCaptureEnabled)
      throws InterruptedException {
    AgentSpan parent = startSpan("test", "parent");
    try (ContextScope ignored = activateSpan(parent)) {
      Context context = Context.current();
      setCaptureEnabled(parentCaptureEnabled);
      CompletableFuture<Void> result = new CompletableFuture<>();
      Thread child =
          Thread.startVirtualThread(
              new Runnable() {
                @Override
                public void run() {
                  try {
                    assertSame(context, Context.current());
                    assertTrue(isCaptureEnabled());
                    setCaptureEnabled(false);
                    for (int i = 0; i < 5; i++) {
                      Thread.sleep(10);
                      assertSame(context, Context.current());
                      assertFalse(isCaptureEnabled());
                    }
                    result.complete(null);
                  } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                  }
                }
              });
      assertEquals(parentCaptureEnabled, isCaptureEnabled());
      assertTrue(child.join(Duration.ofSeconds(10)));
      result.join();
      assertSame(context, Context.current());
      assertEquals(parentCaptureEnabled, isCaptureEnabled());
    } finally {
      parent.finish();
    }
    assertTrue(isCaptureEnabled());
    assertTraces(trace(span().root().operationName("parent")));
  }

  @DisplayName("test Thread.Builder.OfVirtual.start()")
  @Test
  void testBuilderOfVirtualStart() throws InterruptedException, TimeoutException {
    Thread.Builder.OfVirtual threadBuilder = Thread.ofVirtual().name("builder - started");

    new Runnable() {
      @Override
      @Trace(operationName = "parent")
      public void run() {
        // this child will have a span
        threadBuilder.start(new JavaAsyncChild());
        // this child won't
        threadBuilder.start(new JavaAsyncChild(false, false));
        blockUntilChildSpansFinished(1);
      }
    }.run();

    assertConnectedTrace();
  }

  @DisplayName("test Thread.Builder.OfVirtual.unstarted()")
  @Test
  void testBuilderOfVirtualUnstarted() throws InterruptedException, TimeoutException {
    Thread.Builder.OfVirtual threadBuilder = Thread.ofVirtual().name("builder - started");

    new Runnable() {
      @Override
      @Trace(operationName = "parent")
      public void run() {
        // this child will have a span
        threadBuilder.unstarted(new JavaAsyncChild()).start();
        // this child won't
        threadBuilder.unstarted(new JavaAsyncChild(false, false)).start();
        blockUntilChildSpansFinished(1);
      }
    }.run();

    assertConnectedTrace();
  }

  @DisplayName("test Thread.startVirtual()")
  @Test
  void testThreadStartVirtual() throws InterruptedException, TimeoutException {
    new Runnable() {
      @Override
      @Trace(operationName = "parent")
      public void run() {
        // this child will have a span
        Thread.startVirtualThread(new JavaAsyncChild());
        // this child won't
        Thread.startVirtualThread(new JavaAsyncChild(false, false));
        blockUntilChildSpansFinished(1);
      }
    }.run();

    assertConnectedTrace();
  }

  @DisplayName("test Thread.Builder.OfVirtual.factory()")
  @Test
  void testThreadOfVirtualFactory() throws InterruptedException, TimeoutException {
    ThreadFactory factory = Thread.ofVirtual().factory();

    new Runnable() {
      @Override
      @Trace(operationName = "parent")
      public void run() {
        // this child will have a span
        factory.newThread(new JavaAsyncChild()).start();
        // this child won't
        factory.newThread(new JavaAsyncChild(false, false)).start();
        blockUntilChildSpansFinished(1);
      }
    }.run();

    assertConnectedTrace();
  }

  @DisplayName("test nested virtual threads")
  @Test
  void testNestedVirtualThreads() throws InterruptedException, TimeoutException {
    Thread.Builder.OfVirtual threadBuilder = Thread.ofVirtual();

    new Runnable() {
      @Trace(operationName = "parent")
      @Override
      public void run() {
        threadBuilder.start(
            new Runnable() {
              @Trace(operationName = "child")
              @Override
              public void run() {
                threadBuilder.start(
                    new Runnable() {
                      @Trace(operationName = "great-child")
                      @Override
                      public void run() {
                        threadBuilder.start(
                            new Runnable() {
                              @Trace(operationName = "great-great-child")
                              @Override
                              public void run() {
                                System.out.println("complete");
                              }
                            });
                      }
                    });
              }
            });
      }
    }.run();

    // Block test thread until child spans are reported
    blockUntilTracesMatch(traces -> traces.size() == 1 && traces.get(0).size() == 4);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("child"),
            span().childOfPrevious().operationName("great-child"),
            span().childOfPrevious().operationName("great-great-child")));
  }

  /** Verifies the parent / child span relation. */
  void assertConnectedTrace() {
    assertTraces(
        trace(
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("asyncChild")));
  }

  private static void tryUnmount() {
    try {
      // Multiple sleeps to attempt triggering repeated park/unpark cycles.
      // This is not guaranteed to work, but there is no API to force mount/unmount.
      for (int i = 0; i < 5; i++) {
        Thread.sleep(10);
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
