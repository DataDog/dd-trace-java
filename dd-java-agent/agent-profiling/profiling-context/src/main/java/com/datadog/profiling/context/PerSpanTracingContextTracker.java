package com.datadog.profiling.context;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.function.ToIntFunction;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.relocate.api.RatelimitedLogger;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Delayed;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.jctools.maps.NonBlockingHashMapLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PerSpanTracingContextTracker implements TracingContextTracker {
  private static final Logger log = LoggerFactory.getLogger(PerSpanTracingContextTracker.class);

  private static final ByteBuffer EMPTY_DATA = ByteBuffer.allocate(0);

  static final int TRANSITION_STARTED = 0;
  static final int TRANSITION_MAYBE_FINISHED = 1;
  static final int TRANSITION_NONE = -1;
  static final int TRANSITION_FINISHED = 2;

  public interface TimeTicksProvider {
    TimeTicksProvider SYSTEM =
        new TimeTicksProvider() {
          @Override
          public long ticks() {
            return System.nanoTime();
          }

          @Override
          public long frequency() {
            return 1_000_000_000L; // nanosecond frequency
          }
        };

    long ticks();

    long frequency();
  }

  static final class DelayedTrackerImpl implements TracingContextTracker.DelayedTracker {
    volatile PerSpanTracingContextTracker ref;

    DelayedTrackerImpl(PerSpanTracingContextTracker ref) {
      this.ref = ref;
    }

    @Override
    public void cleanup() {
      PerSpanTracingContextTracker instance = ref;
      if (instance != null) {
        instance.release();
        /*
        It is important to remove the reference such that the tracker, and transitively the span
        is not held by the delayed instance until its timeout
        */
        releaseRef();
      }
    }

    void releaseRef() {
      ref = null;
    }

    @Override
    public long getDelay(TimeUnit unit) {
      PerSpanTracingContextTracker instance = ref;
      if (ref != null) {
        return unit.convert(
            instance.lastTransitionTimestamp + instance.inactivityDelay - System.nanoTime(),
            TimeUnit.NANOSECONDS);
      }
      // if the rerence was cleared this instance is ready for immediate removal from the queue
      return -1;
    }

    @Override
    public int compareTo(Delayed o) {
      if (o instanceof PerSpanTracingContextTracker.DelayedTrackerImpl) {
        PerSpanTracingContextTracker thiz = ref;
        PerSpanTracingContextTracker other = ((DelayedTrackerImpl) o).ref;
        return Long.compare(
            thiz != null ? thiz.lastTransitionTimestamp : -1,
            other != null ? other.lastTransitionTimestamp : -1);
      }
      return 0;
    }
  }

  private static final RatelimitedLogger warnlog =
      new RatelimitedLogger(
          LoggerFactory.getLogger(PerSpanTracingContextTracker.class), 30, TimeUnit.SECONDS);

  private static final AtomicReferenceFieldUpdater<PerSpanTracingContextTracker, DelayedTrackerImpl>
      DELAYED_TRACKER_REF_UPDATER =
          AtomicReferenceFieldUpdater.newUpdater(
              PerSpanTracingContextTracker.class, DelayedTrackerImpl.class, "delayedTrackerRef");
  private static final long TRANSITION_MAYBE_FINISHED_MASK =
      (long) (TRANSITION_MAYBE_FINISHED) << 62;
  private static final long TRANSITION_FINISHED_MASK = (long) (TRANSITION_FINISHED) << 62;
  static final long TRANSITION_MASK = TRANSITION_FINISHED_MASK | TRANSITION_MAYBE_FINISHED_MASK;
  static final long TIMESTAMP_MASK = ~TRANSITION_MASK;

  private static final int SPAN_ACTIVATION_DATA_LIMIT;

  private final long inactivityDelay;

  private final NonBlockingHashMapLong<LongSequence> threadSequences =
      new NonBlockingHashMapLong<>(64);
  private final long startTimestampTicks;
  private final long startTimestampNanos;
  private final long delayedActivationTimestamp;
  private final Allocator allocator;
  private static final AtomicIntegerFieldUpdater<PerSpanTracingContextTracker> releasedUpdater =
      AtomicIntegerFieldUpdater.newUpdater(PerSpanTracingContextTracker.class, "released");
  private volatile int released = 0;
  private final AgentSpan span;
  private final TimeTicksProvider timeTicksProvider;
  private final IntervalSequencePruner sequencePruner;

  private final long initialThreadId;
  private static final AtomicIntegerFieldUpdater<PerSpanTracingContextTracker> initializedUpdater =
      AtomicIntegerFieldUpdater.newUpdater(PerSpanTracingContextTracker.class, "initialized");
  private volatile int initialized = 0;
  private volatile boolean truncated = false;
  private final int maxDataSize;

  private static final AtomicReferenceFieldUpdater<PerSpanTracingContextTracker, ByteBuffer>
      persistedUpdater =
          AtomicReferenceFieldUpdater.newUpdater(
              PerSpanTracingContextTracker.class, ByteBuffer.class, "persisted");
  private volatile ByteBuffer persisted = null;

  private long lastTransitionTimestamp = -1;

  private volatile DelayedTrackerImpl delayedTrackerRef = null;

  private final AtomicInteger inFlightSpans;

  static {
    SPAN_ACTIVATION_DATA_LIMIT =
        ConfigProvider.getInstance()
            .getInteger(
                ProfilingConfig.PROFILING_TRACING_CONTEXT_MAX_SIZE,
                ProfilingConfig.PROFILING_TRACING_CONTEXT_MAX_SIZE_DEFAULT);
  }

  PerSpanTracingContextTracker(
      Allocator allocator,
      AgentSpan span,
      TimeTicksProvider timeTicksProvider,
      IntervalSequencePruner sequencePruner,
      int maxDataSize) {
    this(allocator, span, timeTicksProvider, sequencePruner, -1L, maxDataSize, null);
  }

  PerSpanTracingContextTracker(
      Allocator allocator,
      AgentSpan span,
      TimeTicksProvider timeTicksProvider,
      IntervalSequencePruner sequencePruner,
      long inactivityDelay) {
    this(
        allocator,
        span,
        timeTicksProvider,
        sequencePruner,
        inactivityDelay,
        SPAN_ACTIVATION_DATA_LIMIT,
        null);
  }

  PerSpanTracingContextTracker(
      Allocator allocator,
      AgentSpan span,
      TimeTicksProvider timeTicksProvider,
      IntervalSequencePruner sequencePruner,
      long inactivityDelay,
      int maxDataSize,
      AtomicInteger inFlightSpans) {
    this.startTimestampTicks = timeTicksProvider.ticks();
    this.startTimestampNanos = currentTimeNanos();
    this.timeTicksProvider = timeTicksProvider;
    this.sequencePruner = sequencePruner;
    this.span = span;
    this.allocator = allocator;
    this.initialThreadId = Thread.currentThread().getId();
    this.lastTransitionTimestamp = System.nanoTime();
    this.inactivityDelay = inactivityDelay;
    this.delayedActivationTimestamp = timeTicksProvider.ticks();
    this.maxDataSize = maxDataSize;
    this.inFlightSpans = inFlightSpans;
  }

  private static long currentTimeNanos() {
    Instant now = Instant.now();
    return now.getEpochSecond() * 1_000_000_000L + now.getNano();
  }

  @Override
  public void activateContext() {
    activateContext(Thread.currentThread().getId());
  }

  private void activateContext(long threadId) {
    activateContext(threadId, accessTimestamp());
  }

  void activateContext(long threadId, long timestamp) {
    storeDelayedActivation();
    long tsDiff = timestamp - startTimestampTicks;
    long masked = maskActivation(tsDiff);
    store(threadId, masked, true);
  }

  private long accessTimestamp() {
    long ts = timeTicksProvider.ticks();
    lastTransitionTimestamp = System.nanoTime();
    return ts;
  }

  static long maskActivation(long value) {
    return value & TIMESTAMP_MASK;
  }

  static long maskDeactivation(long value, boolean maybe) {
    return (value & TIMESTAMP_MASK)
        | (maybe ? TRANSITION_MAYBE_FINISHED_MASK : TRANSITION_FINISHED_MASK);
  }

  @Override
  public void deactivateContext() {
    deactivateContext(Thread.currentThread().getId(), false);
  }

  @Override
  public void maybeDeactivateContext() {
    deactivateContext(Thread.currentThread().getId(), true);
  }

  private void deactivateContext(long threadId, boolean maybe) {
    deactivateContext(threadId, accessTimestamp(), maybe);
  }

  void deactivateContext(long threadId, long timestamp, boolean maybe) {
    storeDelayedActivation();
    long tsDiff = timestamp - startTimestampTicks;
    long masked = maskDeactivation(tsDiff, maybe);
    // store the transition even if it would cross the limit - for the sake of interval completeness
    store(threadId, masked, false);
  }

  @Override
  public byte[] persist() {
    AtomicReference<byte[]> bytes = new AtomicReference<>();
    persist(
        byteBuffer -> {
          byte[] dataBytes = Arrays.copyOf(byteBuffer.array(), byteBuffer.limit());
          bytes.set(dataBytes);
          return dataBytes.length;
        });
    return bytes.get();
  }

  @Override
  public int persist(ToIntFunction<ByteBuffer> dataConsumer) {
    if (dataConsumer == null) {
      return 0;
    }
    ByteBuffer data = null;
    if (!persistedUpdater.compareAndSet(this, null, EMPTY_DATA)) {
      // busy wait for the data to become available
      while ((data = persisted) == EMPTY_DATA) {
        Thread.yield();
      }
    } else {
      try {
        if (released != 0) {
          // tracker was released without persisting the data
          return 0;
        }
        data = encodeIntervals();
      } finally {
        // make sure the other threads do not stay blocked even if there is an exception thrown
        persistedUpdater.compareAndSet(this, EMPTY_DATA, data);
      }
    }
    return dataConsumer.applyAsInt(data.duplicate());
  }

  LongIterator pruneIntervals(LongSequence sequence) {
    return sequencePruner.pruneIntervals(sequence, timeTicksProvider.ticks() - startTimestampTicks);
  }

  @Override
  public boolean release() {
    boolean result = releaseThreadSequences();
    releaseDelayed();
    if (inFlightSpans != null) {
      inFlightSpans.decrementAndGet();
    }
    return result;
  }

  private boolean releaseThreadSequences() {
    // the released flag needs to be set first such that it would prevent using the resourcese being
    // released from 'persist()' method
    if (releasedUpdater.compareAndSet(this, 0, 1)) {
      log.trace("Releasing tracing context for span {}", span);
      // now let's wait for any date being currently persisted
      // 'persist()' method guarantees that the 'persisted' value will become != EMPTY_DATA
      while (persisted == EMPTY_DATA) {
        Thread.yield();
      }
      // it is safe to cleanup the resources
      threadSequences.values().forEach(LongSequence::release);
      threadSequences.clear();
      return true;
    }
    return false;
  }

  void releaseDelayed() {
    DelayedTrackerImpl delayed = DELAYED_TRACKER_REF_UPDATER.getAndSet(this, null);
    if (delayed != null) {
      delayed.releaseRef();
    }
  }

  @Override
  public int getVersion() {
    return 1;
  }

  @Override
  public DelayedTracker asDelayed() {
    return DELAYED_TRACKER_REF_UPDATER.updateAndGet(
        this, prev -> prev != null ? prev : new DelayedTrackerImpl(this));
  }

  public boolean isTruncated() {
    return truncated;
  }

  private void store(long threadId, long value, boolean obeyLimit) {
    if (released != 0) {
      return;
    }
    int added = 0;
    // this is a thread map so there will be no concurrent attempts to add a new key
    // therefore it is safe to use get-put sequence without the risk of racing
    LongSequence sequence = threadSequences.get(threadId);
    if (sequence == null) {
      sequence = new LongSequence(allocator, maxDataSize);
      threadSequences.put(threadId, sequence);
    }
    synchronized (sequence) {
      added = sequence.add(value, obeyLimit);
    }
    if (added == -1) {
      warnlog.warn(
          "Attempting to add transition to already released context - losing tracing context data");
    } else if (added == -2) {
      if (!truncated) {
        warnlog.warn(
            "Profiling Context Buffer size limit reached ({} bytes) - losing tracing context data",
            maxDataSize);
        truncated = true;
      }
    } else if (added == 0) {
      if (!truncated) {
        warnlog.warn(
            "Profiling Context Buffer is full ({}/{} bytes) - losing tracing context data",
            sequence.getCapacity(),
            sequence.sizeInBytes());
        truncated = true;
      }
    }
  }

  /**
   * This method is assumed to be called from one thread at a time (eg. when a trace is being
   * serialized) Any concurrent invocation will have unpredictable results.
   *
   * @return the {@linkplain ByteBuffer} instance containing the serialized data
   */
  private ByteBuffer encodeIntervals() {
    int encodedDataLimit =
        (maxDataSize * 3) / 4; // encoded data is in base64, growing by 4/3 in size
    int totalSequenceBufferSize = 0;
    long[] threadIds = shuffleArray(threadSequences.keySetLong());
    for (LongSequence sequence : threadSequences.values()) {
      int size = sequence.captureSize();
      totalSequenceBufferSize +=
          (size + 8); // each sequence can receive a synthetic 'start' with length of 8 bytes
    }

    IntervalEncoder encoder =
        new IntervalEncoder(
            startTimestampNanos,
            timeTicksProvider.frequency() / 1_000_000L,
            threadIds.length,
            totalSequenceBufferSize);
    int threadCount = 0;
    outer:
    for (long threadId : threadIds) {
      threadCount++;
      IntervalEncoder.ThreadEncoder threadEncoder = encoder.startThread(threadId);
      LongSequence rawIntervals = threadSequences.get(threadId);
      int sequenceSize = rawIntervals.getCapturedSize();

      if (sequenceSize == -1) {
        // this should never happen given the assumption under which this method is executed
        // but - just in case, log a warning and skip the sequence
        log.warn("Context interval sequence for thread id {} was not captured", threadId);
        continue;
      }

      /*
      The only potential contention here will be between the tracked thread and the context summarization
      when the trace and span is being serialized. At that moment, however, the possibility of code related
      to that span still being executed in some other thread is pretty low. Therefore, most of the time the lock
      will be uncontented.
       */
      synchronized (rawIntervals) {
        LongIterator iterator = pruneIntervals(rawIntervals);
        sequenceSize =
            rawIntervals
                .getCapturedSize(); // refetch the captured size as it may have been modified by
        // pruning
        int sequenceIndex = 0;
        while (iterator.hasNext() && sequenceIndex++ < sequenceSize) {
          long from = iterator.next();
          long maskedFrom = (from & TIMESTAMP_MASK);
          if (iterator.hasNext()) {
            long till = iterator.next();
            long maskedTill = (till & TIMESTAMP_MASK);
            if (maskedTill > maskedFrom) {
              threadEncoder.recordInterval(maskedFrom, maskedTill);
              if (encoder.getDataSize() > encodedDataLimit) {
                threadEncoder.finish();
                truncated = true;
                break outer;
              }
            }
          }
        }
      }
      threadEncoder.finish();
    }
    ByteBuffer buffer = encoder.finish(threadCount);
    return buffer;
  }

  private void storeDelayedActivation() {
    if (initializedUpdater.compareAndSet(this, 0, 1)) {
      log.trace("Storing delayed activation for span {}", span);
      activateContext(initialThreadId, delayedActivationTimestamp);
    }
  }

  // Fisher–Yates shuffle
  private static long[] shuffleArray(long[] array) {
    Random rnd = ThreadLocalRandom.current();
    for (int i = array.length - 1; i > 0; i--) {
      int index = rnd.nextInt(i + 1);
      long item = array[index];
      array[index] = array[i];
      array[i] = item;
    }
    return array;
  }
}
