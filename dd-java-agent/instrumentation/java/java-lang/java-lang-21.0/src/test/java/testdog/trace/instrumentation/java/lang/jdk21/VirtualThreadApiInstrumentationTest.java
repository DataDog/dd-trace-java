package testdog.trace.instrumentation.java.lang.jdk21;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.Trace;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class VirtualThreadApiInstrumentationTest extends AbstractInstrumentationTest {

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
    CountDownLatch latch = new CountDownLatch(3);

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
                                latch.countDown();
                              }
                            });
                        latch.countDown();
                      }
                    });
                latch.countDown();
              }
            });
      }
    }.run();

    latch.await();

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().isRoot().withOperationName("parent"),
            span().childOfPrevious().withOperationName("child"),
            span().childOfPrevious().withOperationName("great-child"),
            span().childOfPrevious().withOperationName("great-great-child")));
  }

  /** Verifies the parent / child span relation. */
  void assertConnectedTrace() {
    assertTraces(
        trace(
            span().isRoot().withOperationName("parent"),
            span().childOfPrevious().withOperationName("asyncChild")));
  }
}
