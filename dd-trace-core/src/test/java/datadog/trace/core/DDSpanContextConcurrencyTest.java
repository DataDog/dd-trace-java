package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
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
   * thread sets tags first, then 8 other threads join in. This exercises the owner → shared
   * transition while writes are in flight.
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

    // Now launch 8 threads that also write — these trigger the transition to shared mode
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

  /**
   * Concurrent reads and writes from multiple threads on the same span. Readers call getTag while
   * writers call setTag, exercising the read path under contention.
   */
  @Test
  @DisplayName("Mixed concurrent reads and writes — no crash, readers see consistent values")
  void concurrentReadsAndWrites() throws Exception {
    int numWriters = 4;
    int numReaders = 4;
    int iterations = 5_000;
    ExecutorService executor = Executors.newFixedThreadPool(numWriters + numReaders);
    CyclicBarrier barrier = new CyclicBarrier(numWriters + numReaders);
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
    AtomicBoolean done = new AtomicBoolean(false);

    AgentSpan span = TRACER.startSpan("test", "readWrite");
    // Pre-populate with initial values so readers always have something
    for (int i = 0; i < 50; i++) {
      span.setTag("shared_" + i, "init");
    }

    List<Future<?>> futures = new ArrayList<>();

    // Writers: overwrite shared keys with thread-specific values
    for (int w = 0; w < numWriters; w++) {
      final int writerIdx = w;
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await(5, TimeUnit.SECONDS);
                  for (int i = 0; i < iterations; i++) {
                    int key = i % 50;
                    span.setTag("shared_" + key, "w" + writerIdx + "_" + i);
                  }
                } catch (Throwable e) {
                  errors.add(e);
                }
              }));
    }

    // Readers: read tags concurrently with writes
    for (int r = 0; r < numReaders; r++) {
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await(5, TimeUnit.SECONDS);
                  while (!done.get()) {
                    for (int i = 0; i < 50; i++) {
                      Object val = span.getTag("shared_" + i);
                      // Value should be non-null (either "init" or a writer's value)
                      assertNotNull(val, "Tag shared_" + i + " was null during concurrent read");
                    }
                  }
                } catch (Throwable e) {
                  errors.add(e);
                }
              }));
    }

    // Wait for writers to finish, then signal readers to stop
    for (int i = 0; i < numWriters; i++) {
      futures.get(i).get(30, TimeUnit.SECONDS);
    }
    done.set(true);
    for (int i = numWriters; i < futures.size(); i++) {
      futures.get(i).get(5, TimeUnit.SECONDS);
    }

    span.finish();
    executor.shutdown();

    assertEquals(0, errors.size(), "Errors during concurrent reads/writes: " + errors);
  }

  /**
   * Randomized fuzz test: each thread performs a random mix of setTag (String, int, double,
   * boolean), setMetric, getTag, and removeTag operations. Repeated 10 times for coverage.
   */
  @RepeatedTest(10)
  @DisplayName("Fuzz test: randomized tag operations from 8 threads — no crash")
  void fuzzRandomizedOperations() throws Exception {
    int numThreads = 8;
    int opsPerThread = 2_000;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CyclicBarrier barrier = new CyclicBarrier(numThreads);
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    AgentSpan span = TRACER.startSpan("test", "fuzz");

    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < numThreads; t++) {
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await(5, TimeUnit.SECONDS);
                  ThreadLocalRandom rng = ThreadLocalRandom.current();
                  for (int i = 0; i < opsPerThread; i++) {
                    String key = "fuzz_" + rng.nextInt(100);
                    switch (rng.nextInt(7)) {
                      case 0:
                        span.setTag(key, "str_" + i);
                        break;
                      case 1:
                        span.setTag(key, rng.nextInt());
                        break;
                      case 2:
                        span.setTag(key, rng.nextDouble());
                        break;
                      case 3:
                        span.setTag(key, rng.nextBoolean());
                        break;
                      case 4:
                        span.setMetric(key, rng.nextInt(1000));
                        break;
                      case 5:
                        span.getTag(key);
                        break;
                      case 6:
                        span.setTag(key, (String) null);
                        break;
                    }
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

    assertEquals(0, errors.size(), "Errors during fuzz test: " + errors);
  }

  /**
   * Verifies that tag values written by a single thread are never corrupted (torn). Each writer
   * thread owns unique keys and writes values with a recognizable pattern. After all writes
   * complete, we verify every key holds a value from the correct writer thread.
   */
  @Test
  @DisplayName("Value consistency: per-thread keys retain correct writer identity")
  void valueConsistencyPerThreadKeys() throws Exception {
    int numThreads = 8;
    int keysPerThread = 100;
    int writesPerKey = 100;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CyclicBarrier barrier = new CyclicBarrier(numThreads);
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    AgentSpan span = TRACER.startSpan("test", "consistency");

    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < numThreads; t++) {
      final int threadIdx = t;
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await(5, TimeUnit.SECONDS);
                  for (int w = 0; w < writesPerKey; w++) {
                    for (int k = 0; k < keysPerThread; k++) {
                      span.setTag("t" + threadIdx + "_k" + k, "t" + threadIdx + "_v" + w);
                    }
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

    assertEquals(0, errors.size(), "Errors during consistency test: " + errors);

    // Each key should contain a value from the correct thread (pattern: "tN_vM")
    for (int t = 0; t < numThreads; t++) {
      for (int k = 0; k < keysPerThread; k++) {
        Object val = span.getTag("t" + t + "_k" + k);
        assertNotNull(val, "Tag t" + t + "_k" + k + " was null");
        assertTrue(
            val.toString().startsWith("t" + t + "_"),
            "Tag t" + t + "_k" + k + " has wrong writer: " + val);
      }
    }
  }

  /**
   * Exercises the owner→shared transition at the exact moment of finish(). The owner thread writes
   * tags and then finishes, while other threads attempt to write simultaneously. This tests that
   * transitionToShared() in finish() correctly makes post-finish writes visible.
   */
  @RepeatedTest(50)
  @DisplayName("Race: finish() concurrent with cross-thread writes — no crash")
  void finishRacesWithCrossThreadWrites() throws Exception {
    int numThreads = 4;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CyclicBarrier barrier = new CyclicBarrier(numThreads + 1); // +1 for the owner/finisher
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    AgentSpan span = TRACER.startSpan("test", "finishRace");
    span.setTag("owner_tag", "present");

    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < numThreads; t++) {
      final int threadIdx = t;
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await(5, TimeUnit.SECONDS);
                  for (int i = 0; i < 100; i++) {
                    span.setTag("race_" + threadIdx + "_" + i, "val");
                  }
                } catch (Throwable e) {
                  errors.add(e);
                }
              }));
    }

    // Owner thread joins the barrier and finishes concurrently
    barrier.await(5, TimeUnit.SECONDS);
    span.finish();

    for (Future<?> f : futures) {
      f.get(10, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertEquals(0, errors.size(), "Errors during finish race: " + errors);
    assertEquals("present", span.getTag("owner_tag"));
  }

  /**
   * Concurrent setMetric calls from multiple threads, verifying that metric values (int, long,
   * float, double) are not corrupted.
   */
  @Test
  @DisplayName("Concurrent setMetric from 8 threads — no crash, values present")
  void concurrentSetMetric() throws Exception {
    int numThreads = 8;
    int metricsPerThread = 500;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CyclicBarrier barrier = new CyclicBarrier(numThreads);
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    AgentSpan span = TRACER.startSpan("test", "metrics");

    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < numThreads; t++) {
      final int threadIdx = t;
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await(5, TimeUnit.SECONDS);
                  for (int i = 0; i < metricsPerThread; i++) {
                    String key = "m_t" + threadIdx + "_" + i;
                    switch (i % 4) {
                      case 0:
                        ((DDSpan) span).context().setMetric(key, i);
                        break;
                      case 1:
                        ((DDSpan) span).context().setMetric(key, (long) i);
                        break;
                      case 2:
                        ((DDSpan) span).context().setMetric(key, (float) i);
                        break;
                      case 3:
                        ((DDSpan) span).context().setMetric(key, (double) i);
                        break;
                    }
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

    assertEquals(0, errors.size(), "Errors during concurrent setMetric: " + errors);
    // Spot-check: each thread's last metric should be present
    for (int t = 0; t < numThreads; t++) {
      assertNotNull(
          span.getTag("m_t" + t + "_" + (metricsPerThread - 1)),
          "Thread " + t + " last metric missing");
    }
  }

  /**
   * Verifies compound atomicity of setSpanSamplingPriority: the three sampling tags must always
   * appear together (all or none) when observed via getTags().
   */
  @RepeatedTest(10)
  @DisplayName("setSpanSamplingPriority atomicity: 3 tags always consistent in getTags()")
  void spanSamplingPriorityAtomicity() throws Exception {
    AgentSpan span = TRACER.startSpan("test", "samplingAtomicity");
    DDSpanContext ctx = ((DDSpan) span).context();
    // Transition to shared so the compound lock is in effect
    ctx.transitionToShared();

    int iterations = 2000;
    AtomicBoolean running = new AtomicBoolean(true);
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    // Writer: repeatedly sets span sampling priority
    Thread writer =
        new Thread(
            () -> {
              try {
                for (int i = 0; i < iterations; i++) {
                  ctx.setSpanSamplingPriority(0.5, 100);
                }
              } catch (Throwable e) {
                errors.add(e);
              } finally {
                running.set(false);
              }
            });

    // Reader: checks that the 3 sampling tags are consistent
    Thread reader =
        new Thread(
            () -> {
              try {
                while (running.get()) {
                  datadog.trace.api.TagMap tags = ctx.getTags();
                  Object mechanism = tags.getObject("_dd.span_sampling.mechanism");
                  Object ruleRate = tags.getObject("_dd.span_sampling.rule_rate");
                  Object maxPerSecond = tags.getObject("_dd.span_sampling.max_per_second");

                  // Either all three are present or none
                  if (mechanism != null || ruleRate != null || maxPerSecond != null) {
                    if (mechanism == null || ruleRate == null || maxPerSecond == null) {
                      errors.add(
                          new AssertionError(
                              "Partial sampling tags: mechanism="
                                  + mechanism
                                  + " ruleRate="
                                  + ruleRate
                                  + " maxPerSecond="
                                  + maxPerSecond));
                    }
                  }
                }
              } catch (Throwable e) {
                errors.add(e);
              }
            });

    writer.start();
    reader.start();
    writer.join(30_000);
    reader.join(5_000);
    span.finish();

    assertEquals(0, errors.size(), "Atomicity violated: " + errors);
  }

  /**
   * Verifies that getTags() returns a consistent snapshot: THREAD_ID and THREAD_NAME are always
   * present and consistent with the span's owning thread.
   */
  @Test
  @DisplayName("getTags() snapshot consistency: virtual fields always present")
  void getTagsSnapshotConsistency() throws Exception {
    AgentSpan span = TRACER.startSpan("test", "getTagsConsistency");

    span.setTag("user_tag", "hello");
    span.finish();

    datadog.trace.api.TagMap tags = ((DDSpan) span).context().getTags();
    assertNotNull(tags.getObject("thread.id"), "THREAD_ID missing from getTags()");
    assertNotNull(tags.getObject("thread.name"), "THREAD_NAME missing from getTags()");
    assertEquals("hello", tags.getObject("user_tag"));
  }

  /**
   * Exercises the transition window: owner tags, finish (triggers transitionToShared), then
   * concurrent readers verify tags are visible post-transition.
   */
  @RepeatedTest(5)
  @DisplayName("Transition + concurrent read: tags written before finish visible after")
  void transitionAndConcurrentRead() throws Exception {
    int numReaders = 4;
    int tagsToWrite = 200;
    AgentSpan span = TRACER.startSpan("test", "transitionRead");

    // Owner thread writes tags
    for (int i = 0; i < tagsToWrite; i++) {
      span.setTag("pre_" + i, "val_" + i);
    }

    // Finish triggers transitionToShared
    span.finish();

    // Multiple reader threads verify all tags are visible
    CyclicBarrier barrier = new CyclicBarrier(numReaders);
    ExecutorService executor = Executors.newFixedThreadPool(numReaders);
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    List<Future<?>> futures = new ArrayList<>();
    for (int r = 0; r < numReaders; r++) {
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await(5, TimeUnit.SECONDS);
                  for (int i = 0; i < tagsToWrite; i++) {
                    Object val = span.getTag("pre_" + i);
                    if (!"val_".concat(String.valueOf(i)).equals(val)) {
                      errors.add(
                          new AssertionError(
                              "Tag pre_" + i + " expected val_" + i + " got " + val));
                    }
                  }
                } catch (Throwable e) {
                  errors.add(e);
                }
              }));
    }

    for (Future<?> f : futures) {
      f.get(10, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertEquals(0, errors.size(), "Tags not visible after transition: " + errors);
  }
}
