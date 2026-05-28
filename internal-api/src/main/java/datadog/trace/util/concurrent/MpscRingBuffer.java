package datadog.trace.util.concurrent;

import datadog.trace.api.function.TriConsumer;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
 * producers observe space being freed.
 */
public final class MpscRingBuffer<T> {

  private final T[] slots;

  /**
   * Per-slot publication sequence. Producers write the claimed sequence here as the last step of a
   * publish (release write via {@link AtomicLongArray#set}); the consumer reads it (acquire read)
   * to determine whether the slot at the next position is ready. A slot is considered published for
   * sequence {@code s} iff {@code sequences[s & mask] == s}.
   */
  private final AtomicLongArray publishedSequences;

  private final int capacity;
  private final int mask;

  /** Next sequence to claim. Producers increment via CAS through {@link #PRODUCER_CURSOR}. */
  @SuppressWarnings("unused") // accessed via PRODUCER_CURSOR field updater
  private volatile long producerCursor = -1L;

  @SuppressWarnings("rawtypes") // AtomicLongFieldUpdater cannot reference a parameterized type
  private static final AtomicLongFieldUpdater<MpscRingBuffer> PRODUCER_CURSOR =
      AtomicLongFieldUpdater.newUpdater(MpscRingBuffer.class, "producerCursor");

  /**
   * Highest sequence consumed. Volatile so producers see space freed up; only the consumer thread
   * writes to it.
   */
  private volatile long consumerCursor = -1L;

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
    this.publishedSequences = new AtomicLongArray(capacity);
    for (int i = 0; i < capacity; i++) {
      slots[i] = factory.get();
      // Initial: sentinel "no sequence published here yet" -- anything < 0 works since
      // sequences are 0-based and monotonically increasing.
      publishedSequences.set(i, Long.MIN_VALUE);
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
      if (publishedSequences.get(idx) != nextSeq) break;
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
      if (publishedSequences.get(idx) != nextSeq) break;
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
      if (publishedSequences.get(idx) != nextSeq) break;
      handler.accept(context1, context2, slots[idx]);
      cursor = nextSeq;
      count++;
    }
    if (count > 0) consumerCursor = cursor;
    return count;
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

  /** Mark sequence {@code seq} as published. Release semantics via {@link AtomicLongArray#set}. */
  private void publish(final long seq) {
    publishedSequences.set((int) (seq & mask), seq);
  }

  private static int nextPowerOfTwo(final int n) {
    if (n <= 1) return 1;
    // 32 - leadingZeros(n-1) gives the number of bits needed to represent n-1; shifting 1 by that
    // gives the smallest power of two >= n.
    final int bits = 32 - Integer.numberOfLeadingZeros(n - 1);
    return 1 << bits;
  }
}
