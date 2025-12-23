package testdog.trace.instrumentation.java.lang.jdk21;

import static java.util.Collections.emptyList;
import static java.util.Comparator.comparing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.Trace;
import datadog.trace.core.DDSpan;
import java.util.List;
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

    var trace = getTrace();
    trace.sort(comparing(DDSpan::getStartTimeNano));
    assertEquals(4, trace.size());
    assertEquals("parent", trace.get(0).getOperationName());
    assertEquals("child", trace.get(1).getOperationName());
    assertEquals("great-child", trace.get(2).getOperationName());
    assertEquals("great-great-child", trace.get(3).getOperationName());
    assertEquals(trace.get(0).getSpanId(), trace.get(1).getParentId());
    assertEquals(trace.get(1).getSpanId(), trace.get(2).getParentId());
    assertEquals(trace.get(2).getSpanId(), trace.get(3).getParentId());
  }

  /** Verifies the parent / child span relation. */
  void assertConnectedTrace() {
    var trace = getTrace();
    trace.sort(comparing(DDSpan::getStartTimeNano));
    assertEquals(2, trace.size());
    assertEquals("parent", trace.get(0).getOperationName());
    assertEquals("asyncChild", trace.get(1).getOperationName());
    assertEquals(trace.get(0).getSpanId(), trace.get(1).getParentId());
  }

  List<DDSpan> getTrace() {
    try {
      writer.waitForTraces(1);
      assertEquals(1, writer.size());
      return writer.getFirst();
    } catch (InterruptedException | TimeoutException e) {
      fail("Failed to wait for trace to finish.", e);
      return emptyList();
    }
  }
}
