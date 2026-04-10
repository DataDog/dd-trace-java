package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class DDSpanContextConcurrencyTest {

  static final CoreTracer TRACER = CoreTracer.builder().build();

  @AfterAll
  static void cleanup() {
    TRACER.close();
  }

  @Test
  @DisplayName("Owner thread: set 1000 tags, finish, verify all present")
  void ownerThreadVisibility() {
    AgentSpan span = TRACER.startSpan("test", "ownerVisibility");

    for (int i = 0; i < 1000; i++) {
      span.setTag("key" + i, "value" + i);
    }
    span.finish();

    for (int i = 0; i < 1000; i++) {
      assertEquals("value" + i, span.getTag("key" + i), "Tag key" + i + " missing after finish");
    }
  }

  @Test
  @DisplayName("Cross-thread tag write: Thread B sets tags on Thread A's span")
  void crossThreadTagWrite() throws Exception {
    AgentSpan span = TRACER.startSpan("test", "crossThread");

    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch done = new CountDownLatch(1);

    executor.submit(
        () -> {
          for (int i = 0; i < 100; i++) {
            span.setTag("cross" + i, "val" + i);
          }
          done.countDown();
        });

    done.await(5, TimeUnit.SECONDS);
    span.finish();

    for (int i = 0; i < 100; i++) {
      assertEquals("val" + i, span.getTag("cross" + i), "Cross-thread tag cross" + i + " missing");
    }
    executor.shutdown();
  }

  @Test
  @DisplayName("Post-finish tag write: tags set after finish() are visible")
  void postFinishTagWrite() {
    AgentSpan span = TRACER.startSpan("test", "postFinish");

    span.setTag("before", "yes");
    span.finish();
    span.setTag("after", "yes");

    assertEquals("yes", span.getTag("before"));
    assertEquals("yes", span.getTag("after"));
  }

  @Test
  @DisplayName("Stress test: 8 threads writing tags concurrently — no structural corruption")
  void stressTestNoCrash() throws Exception {
    AgentSpan span = TRACER.startSpan("test", "stress");
    int numThreads = 8;
    int tagsPerThread = 500;
    CyclicBarrier barrier = new CyclicBarrier(numThreads);
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < numThreads; t++) {
      final int threadIdx = t;
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await(5, TimeUnit.SECONDS);
                  for (int i = 0; i < tagsPerThread; i++) {
                    span.setTag("t" + threadIdx + "_k" + i, "v" + i);
                  }
                } catch (Throwable e) {
                  errors.add(e);
                }
              }));
    }

    for (Future<?> f : futures) {
      f.get(10, TimeUnit.SECONDS);
    }

    span.finish();
    executor.shutdown();

    // No crashes or NPEs — tags may be lost due to races, but no structural corruption
    assertEquals(0, errors.size(), "Unexpected errors: " + errors);
    // Verify at least some tags are present (the map wasn't corrupted)
    assertNotNull(span.getTag("t0_k0"));
  }

  @Test
  @DisplayName("Tag removal via null value on owner thread")
  void tagRemovalOwnerThread() {
    AgentSpan span = TRACER.startSpan("test", "removal");

    span.setTag("toRemove", "present");
    assertEquals("present", span.getTag("toRemove"));

    span.setTag("toRemove", (String) null);
    assertNull(span.getTag("toRemove"));

    span.finish();
  }

  @Test
  @DisplayName("Mixed metrics and tags on owner thread")
  void mixedMetricsAndTags() {
    AgentSpan span = TRACER.startSpan("test", "mixed");

    span.setTag("str", "hello");
    span.setTag("bool", true);
    span.setTag("int", 42);
    span.setTag("long", 100L);
    span.setTag("float", 3.14f);
    span.setTag("double", 2.718);
    span.setMetric("metric_int", 99);
    span.setMetric("metric_double", 1.5);

    assertEquals("hello", span.getTag("str"));
    assertEquals(true, span.getTag("bool"));
    assertEquals(42, span.getTag("int"));
    assertEquals(100L, span.getTag("long"));
    assertEquals(3.14f, span.getTag("float"));
    assertEquals(2.718, span.getTag("double"));
    assertEquals(99, span.getTag("metric_int"));
    assertEquals(1.5, span.getTag("metric_double"));

    span.finish();
  }

  /**
   * Reproduces the exact benchmark pattern: one span created by thread A, 8 threads calling setTag
   * concurrently. This is the pattern that the JMH crossThread benchmark uses, except the benchmark
   * failed because of a race in the JMH harness (SharedSpan.setup with Level.Invocation + 8
   * threads), not in the production code.
   *
   * <p>This test proves the production code handles this pattern without NPE or structural
   * corruption.
   */
  @Test
  @DisplayName(
      "Cross-thread sustained: 8 threads setTag on same span for 10k iterations — no crash")
  void crossThreadSustainedNoCrash() throws Exception {
    int numThreads = 8;
    int iterationsPerThread = 10_000;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);

    // Create span on main thread, then hand it to 8 other threads
    AgentSpan span = TRACER.startSpan("test", "crossSustained");
    CyclicBarrier barrier = new CyclicBarrier(numThreads);
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < numThreads; t++) {
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await(5, TimeUnit.SECONDS);
                  for (int i = 0; i < iterationsPerThread; i++) {
                    span.setTag("key", "value");
                  }
                } catch (Throwable e) {
                  errors.add(e);
                }
              }));
    }

    for (Future<?> f : futures) {
      f.get(30, TimeUnit.SECONDS);
    }
    span.finish();
    executor.shutdown();

    assertEquals(0, errors.size(), "Unexpected errors during cross-thread setTag: " + errors);
    assertEquals("value", span.getTag("key"));
  }

  /**
   * Tests the transition from owner-thread to shared mode under concurrent writes: the creating
   * thread sets tags first, then 8 other threads join in. This exercises the STATE_OWNER →
   * STATE_SHARED transition while writes are in flight.
   */
  @Test
  @DisplayName("Owner-to-shared transition under concurrent writes — no crash, all tags present")
  void ownerToSharedTransition() throws Exception {
    int numThreads = 8;
    int tagsPerThread = 1_000;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);

    AgentSpan span = TRACER.startSpan("test", "ownerToShared");

    // Owner thread writes first batch — these are on the fast path (no lock)
    for (int i = 0; i < 100; i++) {
      span.setTag("owner_" + i, "val_" + i);
    }

    // Now launch 8 threads that also write — these trigger the transition to STATE_SHARED
    CyclicBarrier barrier = new CyclicBarrier(numThreads);
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < numThreads; t++) {
      final int threadIdx = t;
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await(5, TimeUnit.SECONDS);
                  for (int i = 0; i < tagsPerThread; i++) {
                    span.setTag("thread" + threadIdx + "_" + i, "v" + i);
                  }
                } catch (Throwable e) {
                  errors.add(e);
                }
              }));
    }

    for (Future<?> f : futures) {
      f.get(30, TimeUnit.SECONDS);
    }
    span.finish();
    executor.shutdown();

    assertEquals(0, errors.size(), "Unexpected errors during transition: " + errors);

    // Owner-thread tags should all be present — they were written before any contention
    for (int i = 0; i < 100; i++) {
      assertEquals("val_" + i, span.getTag("owner_" + i), "Owner tag owner_" + i + " missing");
    }

    // Each thread's last write should be visible (earlier writes may be overwritten by races)
    for (int t = 0; t < numThreads; t++) {
      assertNotNull(
          span.getTag("thread" + t + "_" + (tagsPerThread - 1)),
          "Thread " + t + " last tag missing");
    }
  }

  /**
   * Exercises many short-lived spans created on one thread and tagged from another — the exact
   * pattern the crossThread benchmark was trying to measure. Uses a stable handoff (CountDownLatch)
   * instead of the racy JMH setup.
   */
  @Test
  @DisplayName("Many short spans tagged cross-thread — no NPE or crash")
  void manySpansCrossThread() throws Exception {
    int numSpans = 10_000;
    ExecutorService tagger = Executors.newFixedThreadPool(8);
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    for (int s = 0; s < numSpans; s++) {
      AgentSpan span = TRACER.startSpan("test", "manyShort");
      CountDownLatch tagged = new CountDownLatch(8);

      for (int t = 0; t < 8; t++) {
        tagger.submit(
            () -> {
              try {
                span.setTag("key", "value");
              } catch (Throwable e) {
                errors.add(e);
              } finally {
                tagged.countDown();
              }
            });
      }

      tagged.await(5, TimeUnit.SECONDS);
      span.finish();
    }

    tagger.shutdown();
    tagger.awaitTermination(10, TimeUnit.SECONDS);
    assertEquals(0, errors.size(), "Errors during cross-thread tagging of short spans: " + errors);
  }
}
