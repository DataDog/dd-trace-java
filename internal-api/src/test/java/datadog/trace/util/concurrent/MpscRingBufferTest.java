package datadog.trace.util.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.function.TriConsumer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class MpscRingBufferTest {

  /** Mutable slot used by the tests; replaces the per-publish allocation a real consumer avoids. */
  static final class Slot {
    int value;
    String tag;
  }

  // ============ Construction ============

  @Test
  void capacityRoundsUpToPowerOfTwo() {
    assertEquals(1, new MpscRingBuffer<>(Slot::new, 1).capacity());
    assertEquals(2, new MpscRingBuffer<>(Slot::new, 2).capacity());
    assertEquals(4, new MpscRingBuffer<>(Slot::new, 3).capacity());
    assertEquals(16, new MpscRingBuffer<>(Slot::new, 10).capacity());
    assertEquals(1024, new MpscRingBuffer<>(Slot::new, 1024).capacity());
    assertEquals(2048, new MpscRingBuffer<>(Slot::new, 1025).capacity());
  }

  @Test
  void rejectsNonPositiveCapacityHint() {
    assertThrows(IllegalArgumentException.class, () -> new MpscRingBuffer<>(Slot::new, 0));
    assertThrows(IllegalArgumentException.class, () -> new MpscRingBuffer<>(Slot::new, -1));
  }

  @Test
  void slotsArePreAllocatedFromFactory() {
    AtomicInteger calls = new AtomicInteger();
    MpscRingBuffer<Slot> ring =
        new MpscRingBuffer<>(
            () -> {
              calls.incrementAndGet();
              return new Slot();
            },
            8);
    assertEquals(8, ring.capacity());
    assertEquals(8, calls.get(), "factory must run capacity times during construction");
  }

  // ============ Basic produce / consume ============

  @Test
  void emptyRingIsEmpty() {
    MpscRingBuffer<Slot> ring = new MpscRingBuffer<>(Slot::new, 4);
    assertTrue(ring.isEmpty());
    assertEquals(0, ring.size());
    assertEquals(0, ring.drain(s -> {}));
  }

  @Test
  void singleWriteThenDrain() {
    MpscRingBuffer<Slot> ring = new MpscRingBuffer<>(Slot::new, 4);
    assertTrue(
        ring.tryWrite(
            s -> {
              s.value = 42;
              s.tag = "hello";
            }));
    assertEquals(1, ring.size());

    List<Integer> seen = new ArrayList<>();
    int drained = ring.drain(s -> seen.add(s.value));
    assertEquals(1, drained);
    assertEquals(Arrays.asList(42), seen);
    assertTrue(ring.isEmpty());
  }

  @Test
  void writesPreserveFIFOOrder() {
    MpscRingBuffer<Slot> ring = new MpscRingBuffer<>(Slot::new, 8);
    for (int i = 0; i < 6; i++) {
      final int v = i;
      assertTrue(ring.tryWrite(s -> s.value = v));
    }
    List<Integer> seen = new ArrayList<>();
    ring.drain(s -> seen.add(s.value));
    assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5), seen);
  }

  // ============ Full / drop behavior ============

  @Test
  void writesBeyondCapacityFail() {
    MpscRingBuffer<Slot> ring = new MpscRingBuffer<>(Slot::new, 4);
    for (int i = 0; i < 4; i++) {
      assertTrue(ring.tryWrite(s -> s.value = 0));
    }
    assertFalse(ring.tryWrite(s -> s.value = 0), "5th write must fail when capacity == 4");
    assertEquals(4, ring.size());
  }

  @Test
  void writesResumeAfterDrain() {
    MpscRingBuffer<Slot> ring = new MpscRingBuffer<>(Slot::new, 4);
    for (int i = 0; i < 4; i++) {
      final int v = i;
      assertTrue(ring.tryWrite(s -> s.value = v));
    }
    assertFalse(ring.tryWrite(s -> {}));

    AtomicInteger sum = new AtomicInteger();
    ring.drain(s -> sum.addAndGet(s.value));
    assertEquals(0 + 1 + 2 + 3, sum.get());

    // Now should accept another full round.
    for (int i = 100; i < 104; i++) {
      final int v = i;
      assertTrue(ring.tryWrite(s -> s.value = v));
    }
    assertFalse(ring.tryWrite(s -> {}));
  }

  // ============ Context-passing variants (context first, slot last) ============

  @Test
  void writeAndDrainWithOneContext() {
    MpscRingBuffer<Slot> ring = new MpscRingBuffer<>(Slot::new, 4);
    BiConsumer<Integer, Slot> filler = (ctx, s) -> s.value = ctx;
    assertTrue(ring.tryWrite(7, filler));
    assertTrue(ring.tryWrite(8, filler));

    List<Integer> seen = new ArrayList<>();
    BiConsumer<List<Integer>, Slot> reader = (sink, s) -> sink.add(s.value);
    assertEquals(2, ring.drain(seen, reader));
    assertEquals(Arrays.asList(7, 8), seen);
  }

  @Test
  void writeAndDrainWithTwoContexts() {
    MpscRingBuffer<Slot> ring = new MpscRingBuffer<>(Slot::new, 4);
    TriConsumer<Integer, String, Slot> filler =
        (v, t, s) -> {
          s.value = v;
          s.tag = t;
        };
    assertTrue(ring.tryWrite(1, "a", filler));
    assertTrue(ring.tryWrite(2, "b", filler));

    List<String> tags = new ArrayList<>();
    List<Integer> vals = new ArrayList<>();
    TriConsumer<List<String>, List<Integer>, Slot> reader =
        (ts, vs, s) -> {
          ts.add(s.tag);
          vs.add(s.value);
        };
    assertEquals(2, ring.drain(tags, vals, reader));
    assertEquals(Arrays.asList("a", "b"), tags);
    assertEquals(Arrays.asList(1, 2), vals);
  }

  // ============ Concurrency ============

  @Test
  void manyProducersSingleConsumerSeesEveryWrittenValue() throws InterruptedException {
    final int producers = 8;
    final int perProducer = 50_000;
    final int total = producers * perProducer;

    MpscRingBuffer<Slot> ring = new MpscRingBuffer<>(Slot::new, 1024);

    ExecutorService producerPool = Executors.newFixedThreadPool(producers);
    AtomicInteger dropped = new AtomicInteger();
    AtomicInteger written = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);

    BiConsumer<Integer, Slot> filler = (v, s) -> s.value = v;

    for (int p = 0; p < producers; p++) {
      final int base = p * perProducer;
      producerPool.submit(
          () -> {
            try {
              start.await();
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              return;
            }
            for (int i = 0; i < perProducer; i++) {
              int v = base + i;
              // Spin until the consumer makes room. We're testing correctness, not throughput.
              while (!ring.tryWrite(v, filler)) {
                dropped.incrementAndGet();
                Thread.yield();
              }
              written.incrementAndGet();
            }
          });
    }

    Set<Integer> seen = new HashSet<>(total);
    BiConsumer<Set<Integer>, Slot> reader = (sink, s) -> sink.add(s.value);

    Thread consumer =
        new Thread(
            () -> {
              while (seen.size() < total) {
                if (ring.drain(seen, reader) == 0) Thread.yield();
              }
            },
            "ring-consumer");
    consumer.start();

    start.countDown();
    producerPool.shutdown();
    assertTrue(producerPool.awaitTermination(30, TimeUnit.SECONDS), "producers timed out");
    assertTrue(consumer.isAlive() || seen.size() == total);
    consumer.join(30_000);
    assertFalse(consumer.isAlive(), "consumer timed out");

    assertEquals(total, written.get(), "every producer call should eventually succeed");
    assertEquals(total, seen.size(), "consumer must see every value exactly once");
  }

  @Test
  void sizeReflectsOutstandingWrites() {
    MpscRingBuffer<Slot> ring = new MpscRingBuffer<>(Slot::new, 8);
    assertEquals(0, ring.size());
    ring.tryWrite(s -> {});
    assertEquals(1, ring.size());
    ring.tryWrite(s -> {});
    assertEquals(2, ring.size());
    ring.drain(s -> {});
    assertEquals(0, ring.size());
  }

  @Test
  void throwingFillerStillPublishesSoConsumerDoesntHang() {
    MpscRingBuffer<Slot> ring = new MpscRingBuffer<>(Slot::new, 4);

    // First write succeeds.
    assertTrue(ring.tryWrite(s -> s.value = 1));

    // Second write throws midway through filling. Slot must still be published so the consumer's
    // drain can advance past it.
    RuntimeException boom = new RuntimeException("boom");
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                ring.tryWrite(
                    s -> {
                      s.value = 2;
                      throw boom;
                    }));
    assertSame(boom, thrown);

    // Third write proves the ring's cursors are still healthy after the throw.
    assertTrue(ring.tryWrite(s -> s.value = 3));

    // Consumer drains all three: it must not hang on the partially-filled slot.
    List<Integer> seen = new ArrayList<>();
    int drained = ring.drain(s -> seen.add(s.value));
    assertEquals(3, drained, "consumer must advance past the throwing slot");
    assertEquals(Arrays.asList(1, 2, 3), seen, "throwing slot keeps whatever filler had written");
  }
}
