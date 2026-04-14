package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Concurrency tests for the OptimizedTagMap thread-ownership model. */
public final class TagMapConcurrencyTest {

  /**
   * Creates an OptimizedTagMap directly — ownership semantics only apply to this implementation.
   */
  private static TagMap createMap() {
    return new OptimizedTagMap();
  }

  @Test
  @DisplayName("Owner thread: write 1000 tags via fast path, verify all present")
  void ownerThreadFastPath() {
    TagMap map = createMap();
    map.setOwnerThread(Thread.currentThread());

    for (int i = 0; i < 1000; i++) {
      map.set("key" + i, "value" + i);
    }

    assertEquals(1000, map.size());
    for (int i = 0; i < 1000; i++) {
      assertEquals("value" + i, map.getObject("key" + i));
    }
  }

  @Test
  @DisplayName("Transition: owner writes, transitionToShared, non-owner writes, all visible")
  void transitionMakesWritesVisible() throws Exception {
    TagMap map = createMap();
    map.setOwnerThread(Thread.currentThread());

    // Owner writes
    for (int i = 0; i < 100; i++) {
      map.set("owner" + i, "val" + i);
    }

    map.transitionToShared();

    // Non-owner writes from another thread
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch done = new CountDownLatch(1);
    executor.submit(
        () -> {
          for (int i = 0; i < 100; i++) {
            map.set("other" + i, "val" + i);
          }
          done.countDown();
        });

    done.await(5, TimeUnit.SECONDS);
    executor.shutdown();

    // All writes visible
    for (int i = 0; i < 100; i++) {
      assertEquals("val" + i, map.getObject("owner" + i), "Owner tag owner" + i + " missing");
      assertEquals("val" + i, map.getObject("other" + i), "Other tag other" + i + " missing");
    }
    assertEquals(200, map.size());
  }

  @RepeatedTest(5)
  @DisplayName("Post-transition: 8 threads write concurrently — no crash, no lost keys")
  void postTransitionContention() throws Exception {
    int numThreads = 8;
    int tagsPerThread = 500;
    TagMap map = createMap();
    map.setOwnerThread(Thread.currentThread());
    map.transitionToShared();

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
                    map.set("t" + threadIdx + "_k" + i, "v" + i);
                  }
                } catch (Throwable e) {
                  errors.add(e);
                }
              }));
    }

    for (Future<?> f : futures) {
      f.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertEquals(0, errors.size(), "Unexpected errors: " + errors);
    // Each thread's last write should be present
    for (int t = 0; t < numThreads; t++) {
      assertNotNull(
          map.getObject("t" + t + "_k" + (tagsPerThread - 1)), "Thread " + t + " last tag missing");
    }
  }

  @Test
  @DisplayName("withLock provides compound atomicity: batch is all-or-nothing to observers")
  void withLockCompoundAtomicity() throws Exception {
    TagMap map = createMap();
    map.setOwnerThread(Thread.currentThread());
    map.transitionToShared();

    int batchSize = 10;
    int iterations = 5000;
    AtomicBoolean running = new AtomicBoolean(true);
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    // Writer: writes batches of N tags atomically via withLock
    Thread writer =
        new Thread(
            () -> {
              try {
                for (int iter = 0; iter < iterations; iter++) {
                  final int batch = iter;
                  map.withLock(
                      () -> {
                        for (int i = 0; i < batchSize; i++) {
                          map.set("batch_" + i, "b" + batch);
                        }
                      });
                }
              } catch (Throwable e) {
                errors.add(e);
              } finally {
                running.set(false);
              }
            });

    // Reader: reads all batch tags and verifies they're from the same batch
    Thread reader =
        new Thread(
            () -> {
              try {
                while (running.get()) {
                  map.withLock(
                      () -> {
                        Object first = map.getObject("batch_0");
                        if (first == null) return;
                        for (int i = 1; i < batchSize; i++) {
                          Object val = map.getObject("batch_" + i);
                          if (val != null && !val.equals(first)) {
                            errors.add(
                                new AssertionError(
                                    "Partial batch: batch_0="
                                        + first
                                        + " but batch_"
                                        + i
                                        + "="
                                        + val));
                          }
                        }
                      });
                }
              } catch (Throwable e) {
                errors.add(e);
              }
            });

    writer.start();
    reader.start();
    writer.join(30_000);
    assertFalse(writer.isAlive(), "Writer thread did not terminate");
    reader.join(5_000);
    assertFalse(reader.isAlive(), "Reader thread did not terminate");

    assertEquals(0, errors.size(), "Atomicity violated: " + errors);
  }

  @RepeatedTest(3)
  @DisplayName("size() is consistent under contention: never exceeds total unique keys written")
  void sizeConsistencyUnderContention() throws Exception {
    int numWriters = 4;
    int keysPerWriter = 200;
    TagMap map = createMap();
    map.setOwnerThread(Thread.currentThread());
    map.transitionToShared();

    CyclicBarrier barrier = new CyclicBarrier(numWriters);
    ExecutorService executor = Executors.newFixedThreadPool(numWriters);
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < numWriters; t++) {
      final int threadIdx = t;
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await(5, TimeUnit.SECONDS);
                  for (int i = 0; i < keysPerWriter; i++) {
                    map.set("w" + threadIdx + "_" + i, "v");
                    // Occasionally read size to check consistency
                    int s = map.size();
                    if (s < 0 || s > numWriters * keysPerWriter) {
                      errors.add(new AssertionError("size() out of range: " + s));
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
    executor.shutdown();

    assertEquals(0, errors.size(), "Unexpected errors: " + errors);
    int finalSize = map.size();
    assertTrue(
        finalSize > 0 && finalSize <= numWriters * keysPerWriter,
        "Final size out of range: " + finalSize);
  }

  @RepeatedTest(3)
  @DisplayName("forEach under contention: writer + forEach reader — no crash")
  void forEachUnderContention() throws Exception {
    TagMap map = createMap();
    map.setOwnerThread(Thread.currentThread());
    map.transitionToShared();

    // Pre-populate
    for (int i = 0; i < 50; i++) {
      map.set("pre" + i, "val" + i);
    }

    int iterations = 5000;
    AtomicBoolean running = new AtomicBoolean(true);
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    Thread writer =
        new Thread(
            () -> {
              try {
                for (int i = 0; i < iterations; i++) {
                  map.set("dyn" + (i % 100), "val" + i);
                }
              } catch (Throwable e) {
                errors.add(e);
              } finally {
                running.set(false);
              }
            });

    Thread reader =
        new Thread(
            () -> {
              try {
                while (running.get()) {
                  int[] count = {0};
                  map.forEach(entry -> count[0]++);
                  if (count[0] < 0) {
                    errors.add(new AssertionError("forEach returned negative count"));
                  }
                }
              } catch (Throwable e) {
                errors.add(e);
              }
            });

    writer.start();
    reader.start();
    writer.join(30_000);
    assertFalse(writer.isAlive(), "Writer thread did not terminate");
    reader.join(5_000);
    assertFalse(reader.isAlive(), "Reader thread did not terminate");

    assertEquals(0, errors.size(), "Errors during forEach contention: " + errors);
  }
}
