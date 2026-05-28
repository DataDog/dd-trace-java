package datadog.trace.util.concurrent;

import datadog.trace.api.function.TriConsumer;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

// Disruptor-style cache-line padding around the cursors. The two cursors live on different cache
// lines so consumer-side writes to consumerCursor don't invalidate the line producers read for
// producerCursor (and vice versa). Each padding class declares 7 longs (56 bytes); combined with
// the cursor's own 8 bytes plus the JVM object header, each cursor + its surrounding pad fills
// a 64-byte cache line. The HotSpot field-layout strategy preserves the declaration order across
// the class hierarchy, so this pattern is reliable on all production JVMs we target.

abstract class MpscRingBufferPad0 {
  long p01, p02, p03, p04, p05, p06, p07;
}

abstract class MpscRingBufferProducerCursor extends MpscRingBufferPad0 {
  /** Next sequence to claim. Producers increment via CAS through {@code PRODUCER_CURSOR}. */
  volatile long producerCursor = -1L;
}

abstract class MpscRingBufferPad1 extends MpscRingBufferProducerCursor {
  long p11, p12, p13, p14, p15, p16, p17;
}

abstract class MpscRingBufferConsumerCursor extends MpscRingBufferPad1 {
  /** Highest sequence consumed. Only the consumer thread writes; producers read volatile. */
  volatile long consumerCursor = -1L;
}

abstract class MpscRingBufferPad2 extends MpscRingBufferConsumerCursor {
  long p21, p22, p23, p24, p25, p26, p27;
}

/**
 * Bounded multi-producer / single-consumer ring buffer of pre-allocated {@code T} instances.
 *
 * <p>Each slot is a long-lived {@code T} instance created at construction time via the supplied
 * factory and recycled forever. Producers mutate slots in place via callbacks; the consumer reads
 * them the same way. No allocation occurs per write/read after construction, which is the entire
 * point of this class over a queue of references.
 *
 * <p>The {@code BiConsumer} and {@link TriConsumer} variants take their context object(s)
 * <i>before</i> the slot, matching the convention of {@code TagMap.forEach} and {@code
 * Hashtable.forEach}. That ordering lets callers declare the callback as a {@code static final}
 * non-capturing lambda and pass per-call context at the call site without allocating a closure.
 *
 * <h2>Thread safety contract</h2>
 *
 * <p>The ring buffer is thread-safe for any number of producer threads plus exactly one consumer
 * thread. Calling {@code drain} from multiple threads concurrently is <b>not</b> supported and will
 * corrupt state.
 *
 * <p>For the slot type {@code T}:
 *
 * <ul>
 *   <li><b>Slot fields can be plain</b> ({@code int}, {@code long}, object references) -- they do
 *       <i>not</i> need to be {@code volatile} or guarded by synchronization. Happens-before
 *       between the producer's slot mutation and the consumer's slot read is provided by the ring's
 *       internal publication-sequence machinery: a release write on the per-slot sequence inside
 *       {@code tryWrite}, paired with an acquire read inside {@code drain}.
 *   <li><b>Don't retain slot references past your handler's return.</b> Once a {@code tryWrite}
 *       filler returns, the slot becomes visible to the consumer; once a {@code drain} handler
 *       returns, the slot may be reclaimed by another producer and its fields overwritten. If the
 *       consumer needs to keep any state from a slot, it must extract by value (or copy references)
 *       before returning.
 *   <li><b>Don't expose slot references outside the ring.</b> Treat {@code T} as ring-buffer-owned;
 *       sharing a slot reference with code that doesn't follow the same discipline breaks the
 *       happens-before story.
 * </ul>
 *
 * <p>For producer fillers:
 *
 * <ul>
 *   <li>Filler invocations on the same slot are serialized (one producer wins the sequence CAS), so
 *       the filler can write fields without synchronization.
 *   <li><b>If a filler throws, the slot is published anyway</b> (with whatever the filler had
 *       written so far) and the exception propagates to the caller. This prevents the consumer from
 *       getting stuck waiting for an unfinished slot; the cost is that the consumer may observe a
 *       partially-filled or stale-fielded slot. Fillers should be written to either not throw or to
 *       leave the slot in a state the consumer can recognize and skip.
 * </ul>
 *
 * <h2>Implementation</h2>
 *
 * <p>Producer cursor is CAS-claimed; visibility of a claimed slot to the consumer is gated by a
 * per-slot publication-sequence array. Consumer cursor is updated with a volatile write so
 * producers observe space being freed. Cursors are cache-line-padded against each other (see the
 * {@code MpscRingBufferPad*} hierarchy at the top of this file) and the publication-sequence array
 * is strided so each logical entry occupies a distinct cache line.
 */
public final class MpscRingBuffer<T> extends MpscRingBufferPad2 {

  /**
   * Cache line size in {@code long}-units. 64-byte cache lines on every common CPU we ship to (x86,
   * ARM); 8 bytes per long. Each logical slot in {@link #publishedSequences} is spread out by this
   * stride so adjacent logical sequences don't share a cache line and don't ping-pong between
   * producer cores under heavy contention.
   */
  private static final int CACHE_LINE_LONGS = 8;

  @SuppressWarnings("rawtypes") // AtomicLongFieldUpdater can't take a parameterized class
  private static final AtomicLongFieldUpdater<MpscRingBufferProducerCursor> PRODUCER_CURSOR =
      AtomicLongFieldUpdater.newUpdater(MpscRingBufferProducerCursor.class, "producerCursor");

  private final T[] slots;

  /**
   * Per-slot publication sequence, strided by {@link #CACHE_LINE_LONGS} to avoid false sharing.
   * Producers write the claimed sequence here as the last step of a publish (release write via
   * {@link AtomicLongArray#set}); the consumer reads it (acquire read) to determine whether the
   * slot at the next position is ready. A slot is considered published for sequence {@code s} iff
   * {@code publishedSequences[(s & mask) * CACHE_LINE_LONGS] == s}.
   */
  private final AtomicLongArray publishedSequences;

  private final int capacity;
  private final int mask;

  @SuppressWarnings("unchecked")
  public MpscRingBuffer(final Supplier<T> factory, final int capacityHint) {
    if (capacityHint < 1) {
      throw new IllegalArgumentException("capacityHint must be >= 1, got " + capacityHint);
    }
    this.capacity = nextPowerOfTwo(capacityHint);
    if (this.capacity < 1) {
      throw new IllegalArgumentException("capacity overflow for hint " + capacityHint);
    }
    this.mask = capacity - 1;
    this.slots = (T[]) new Object[capacity];
    this.publishedSequences = new AtomicLongArray(capacity * CACHE_LINE_LONGS);
    for (int i = 0; i < capacity; i++) {
      slots[i] = factory.get();
      // Initial: sentinel "no sequence published here yet" -- anything < 0 works since
      // sequences are 0-based and monotonically increasing.
      publishedSequences.set(i * CACHE_LINE_LONGS, Long.MIN_VALUE);
    }
  }

  public int capacity() {
    return capacity;
  }

  /** Approximate count of slots holding unread items. May briefly exceed capacity under race. */
  public int size() {
    final long p = producerCursor;
    final long c = consumerCursor;
    final long diff = p - c;
    if (diff <= 0) return 0;
    if (diff > capacity) return capacity;
    return (int) diff;
  }

  public boolean isEmpty() {
    return producerCursor == consumerCursor;
  }

  /** {@code true} if the slot was filled and published; {@code false} if the ring is full. */
  public boolean tryWrite(final Consumer<? super T> filler) {
    final long seq = claim();
    if (seq < 0L) return false;
    // publish in finally so a throwing filler doesn't leave the slot un-published -- the
    // consumer would otherwise wait at that sequence forever. See class javadoc.
    try {
      filler.accept(slots[(int) (seq & mask)]);
    } finally {
      publish(seq);
    }
    return true;
  }

  public <C> boolean tryWrite(final C context, final BiConsumer<? super C, ? super T> filler) {
    final long seq = claim();
    if (seq < 0L) return false;
    try {
      filler.accept(context, slots[(int) (seq & mask)]);
    } finally {
      publish(seq);
    }
    return true;
  }

  public <C1, C2> boolean tryWrite(
      final C1 context1,
      final C2 context2,
      final TriConsumer<? super C1, ? super C2, ? super T> filler) {
    final long seq = claim();
    if (seq < 0L) return false;
    try {
      filler.accept(context1, context2, slots[(int) (seq & mask)]);
    } finally {
      publish(seq);
    }
    return true;
  }

  /** Drains all currently-available slots. Returns the count processed. */
  public int drain(final Consumer<? super T> handler) {
    long cursor = consumerCursor;
    int count = 0;
    while (true) {
      final long nextSeq = cursor + 1L;
      final int idx = (int) (nextSeq & mask);
      if (publishedSequences.get(idx * CACHE_LINE_LONGS) != nextSeq) break;
      handler.accept(slots[idx]);
      cursor = nextSeq;
      count++;
    }
    if (count > 0) consumerCursor = cursor;
    return count;
  }

  public <C> int drain(final C context, final BiConsumer<? super C, ? super T> handler) {
    long cursor = consumerCursor;
    int count = 0;
    while (true) {
      final long nextSeq = cursor + 1L;
      final int idx = (int) (nextSeq & mask);
      if (publishedSequences.get(idx * CACHE_LINE_LONGS) != nextSeq) break;
      handler.accept(context, slots[idx]);
      cursor = nextSeq;
      count++;
    }
    if (count > 0) consumerCursor = cursor;
    return count;
  }

  public <C1, C2> int drain(
      final C1 context1,
      final C2 context2,
      final TriConsumer<? super C1, ? super C2, ? super T> handler) {
    long cursor = consumerCursor;
    int count = 0;
    while (true) {
      final long nextSeq = cursor + 1L;
      final int idx = (int) (nextSeq & mask);
      if (publishedSequences.get(idx * CACHE_LINE_LONGS) != nextSeq) break;
      handler.accept(context1, context2, slots[idx]);
      cursor = nextSeq;
      count++;
    }
    if (count > 0) consumerCursor = cursor;
    return count;
  }

  /**
   * Try to claim a contiguous range of {@code n} sequences in a single CAS. Returns {@code null} if
   * the ring doesn't have room for the whole batch -- the caller treats that as "drop all {@code
   * n}", which is the natural shape for callers that batch by a higher-level unit (e.g. one CSS
   * publish per completed trace). When the caller has a list of N items to write, this amortizes
   * producer-cursor contention from O(N) CASes to O(1) per call.
   *
   * <p>The returned {@link Batch} must be filled via {@link Batch#fillAndPublish} exactly {@code n}
   * times. Under-publishing leaves the ring stuck at the unfilled sequence -- the consumer waits
   * there forever. Over-publishing throws {@link IllegalStateException}.
   *
   * @throws IllegalArgumentException if {@code n &lt; 1}
   */
  public Batch tryClaim(final int n) {
    if (n < 1) {
      throw new IllegalArgumentException("n must be >= 1, got " + n);
    }
    while (true) {
      final long current = producerCursor;
      // Stale read of consumerCursor is fine: a false "full" reading just causes a drop, and a
      // real one is correctly identified because consumerCursor only advances.
      final long consumed = consumerCursor;
      final long next = current + n;
      if (next - consumed > capacity) {
        return null;
      }
      if (PRODUCER_CURSOR.compareAndSet(this, current, next)) {
        // Claimed sequences [current + 1, next] inclusive (== n sequences total).
        return new Batch(current + 1L, n);
      }
      // CAS failure -> another producer claimed; retry.
    }
  }

  /** CAS-claim the next sequence, or return {@code -1} if the ring is full. */
  private long claim() {
    while (true) {
      final long current = producerCursor;
      // Stale read of consumerCursor is fine: a false "full" reading just causes a drop, and
      // a real "full" reading is correctly identified because consumerCursor only advances.
      final long consumed = consumerCursor;
      if (current - consumed >= capacity) {
        return -1L;
      }
      final long next = current + 1L;
      if (PRODUCER_CURSOR.compareAndSet(this, current, next)) {
        return next;
      }
      // CAS failure -> another producer claimed; retry.
    }
  }

  /**
   * Handle returned by {@link MpscRingBuffer#tryClaim}. Holds a contiguous range of pre-claimed
   * sequences belonging to the producer thread that called {@code tryClaim}; the caller must fill
   * and publish each via {@link #fillAndPublish}.
   *
   * <p><b>Not thread-safe</b> -- the producer thread owns it for the lifetime of the call. Do not
   * share across threads.
   */
  public final class Batch {
    private final long startSeq;
    private final int size;
    private int published;

    Batch(final long startSeq, final int size) {
      this.startSeq = startSeq;
      this.size = size;
    }

    /** Total slots in this batch (the {@code n} passed to {@code tryClaim}). */
    public int size() {
      return size;
    }

    /** Slots not yet filled. */
    public int remaining() {
      return size - published;
    }

    public void fillAndPublish(final Consumer<? super T> filler) {
      final long seq = nextSeq();
      final int idx = (int) (seq & mask);
      try {
        filler.accept(slots[idx]);
      } finally {
        publishedSequences.set(idx * CACHE_LINE_LONGS, seq);
      }
    }

    public <C> void fillAndPublish(final C context, final BiConsumer<? super C, ? super T> filler) {
      final long seq = nextSeq();
      final int idx = (int) (seq & mask);
      try {
        filler.accept(context, slots[idx]);
      } finally {
        publishedSequences.set(idx * CACHE_LINE_LONGS, seq);
      }
    }

    public <C1, C2> void fillAndPublish(
        final C1 context1,
        final C2 context2,
        final TriConsumer<? super C1, ? super C2, ? super T> filler) {
      final long seq = nextSeq();
      final int idx = (int) (seq & mask);
      try {
        filler.accept(context1, context2, slots[idx]);
      } finally {
        publishedSequences.set(idx * CACHE_LINE_LONGS, seq);
      }
    }

    private long nextSeq() {
      if (published >= size) {
        throw new IllegalStateException(
            "Batch over-published: size=" + size + " published=" + published);
      }
      final long seq = startSeq + published;
      published++;
      return seq;
    }
  }

  /** Mark sequence {@code seq} as published. Release semantics via {@link AtomicLongArray#set}. */
  private void publish(final long seq) {
    publishedSequences.set(((int) (seq & mask)) * CACHE_LINE_LONGS, seq);
  }

  private static int nextPowerOfTwo(final int n) {
    if (n <= 1) return 1;
    // 32 - leadingZeros(n-1) gives the number of bits needed to represent n-1; shifting 1 by that
    // gives the smallest power of two >= n.
    final int bits = 32 - Integer.numberOfLeadingZeros(n - 1);
    return 1 << bits;
  }
}
