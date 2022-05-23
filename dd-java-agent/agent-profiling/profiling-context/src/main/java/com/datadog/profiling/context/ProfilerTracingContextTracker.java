package com.datadog.profiling.context;

import datadog.trace.api.function.ToIntFunction;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.relocate.api.RatelimitedLogger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProfilerTracingContextTracker implements TracingContextTracker {
  private static final Logger log = LoggerFactory.getLogger(ProfilerTracingContextTracker.class);

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
    volatile ProfilerTracingContextTracker ref;

    DelayedTrackerImpl(ProfilerTracingContextTracker ref) {
      this.ref = ref;
    }

    @Override
    public void cleanup() {
      ProfilerTracingContextTracker instance = ref;
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
      ProfilerTracingContextTracker instance = ref;
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
      if (o instanceof ProfilerTracingContextTracker.DelayedTrackerImpl) {
        ProfilerTracingContextTracker thiz = ref;
        ProfilerTracingContextTracker other = ((DelayedTrackerImpl) o).ref;
        return Long.compare(
            thiz != null ? thiz.lastTransitionTimestamp : -1,
            other != null ? other.lastTransitionTimestamp : -1);
      }
      return 0;
    }
  }

  private static final RatelimitedLogger warnlog =
      new RatelimitedLogger(
          LoggerFactory.getLogger(ProfilerTracingContextTracker.class), 30, TimeUnit.SECONDS);

  private static final AtomicReferenceFieldUpdater<
          ProfilerTracingContextTracker, DelayedTrackerImpl>
      DELAYED_TRACKER_REF_UPDATER =
          AtomicReferenceFieldUpdater.newUpdater(
              ProfilerTracingContextTracker.class, DelayedTrackerImpl.class, "delayedTrackerRef");
  private static final long TRANSITION_MAYBE_FINISHED_MASK =
      (long) (TRANSITION_MAYBE_FINISHED) << 62;
  private static final long TRANSITION_FINISHED_MASK = (long) (TRANSITION_FINISHED) << 62;
  static final long TRANSITION_MASK = TRANSITION_FINISHED_MASK | TRANSITION_MAYBE_FINISHED_MASK;
  static final long TIMESTAMP_MASK = ~TRANSITION_MASK;

  private static final int SPAN_ACTIVATION_DATA_LIMIT = 20000; // at most 20000 bytes

  private final long inactivityDelay;

  private final ConcurrentMap<Long, LongSequence> threadSequences = new ConcurrentHashMap<>(64);
  private final long startTimestampTicks;
  private final long startTimestampMillis;
  private final long delayedActivationTimestamp;
  private final Allocator allocator;
  private static final AtomicIntegerFieldUpdater<ProfilerTracingContextTracker> releasedUpdater =
      AtomicIntegerFieldUpdater.newUpdater(ProfilerTracingContextTracker.class, "released");
  private volatile int released = 0;
  private final AgentSpan span;
  private final TimeTicksProvider timeTicksProvider;
  private final IntervalSequencePruner sequencePruner;

  private final long initialThreadId;
  private static final AtomicIntegerFieldUpdater<ProfilerTracingContextTracker> initializedUpdater =
      AtomicIntegerFieldUpdater.newUpdater(ProfilerTracingContextTracker.class, "initialized");
  private volatile int initialized = 0;
  private volatile boolean truncated = false;
  private final int maxDataSize;

  private static final AtomicReferenceFieldUpdater<ProfilerTracingContextTracker, ByteBuffer>
      persistedUpdater =
          AtomicReferenceFieldUpdater.newUpdater(
              ProfilerTracingContextTracker.class, ByteBuffer.class, "persisted");
  private volatile ByteBuffer persisted = null;

  private long lastTransitionTimestamp = -1;

  private volatile DelayedTrackerImpl delayedTrackerRef = null;

  ProfilerTracingContextTracker(
      Allocator allocator,
      AgentSpan span,
      TimeTicksProvider timeTicksProvider,
      IntervalSequencePruner sequencePruner,
      int maxDataSize) {
    this(allocator, span, timeTicksProvider, sequencePruner, -1L, maxDataSize);
  }

  ProfilerTracingContextTracker(
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
        SPAN_ACTIVATION_DATA_LIMIT);
  }

  ProfilerTracingContextTracker(
      Allocator allocator,
      AgentSpan span,
      TimeTicksProvider timeTicksProvider,
      IntervalSequencePruner sequencePruner,
      long inactivityDelay,
      int maxDataSize) {
    this.startTimestampTicks = timeTicksProvider.ticks();
    this.startTimestampMillis = System.currentTimeMillis();
    this.timeTicksProvider = timeTicksProvider;
    this.sequencePruner = sequencePruner;
    this.span = span;
    this.allocator = allocator;
    this.initialThreadId = Thread.currentThread().getId();
    this.lastTransitionTimestamp = System.nanoTime();
    this.inactivityDelay = inactivityDelay;
    this.delayedActivationTimestamp = timeTicksProvider.ticks();
    this.maxDataSize = maxDataSize;
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
    LongSequence sequence = threadSequences.computeIfAbsent(threadId, this::newLongSequence);
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
            "Profiling Context Buffer is full ({} bytes) - losing tracing context data",
            sequence.sizeInBytes());
        truncated = true;
      }
    }
  }

  private LongSequence newLongSequence(long dummy) {
    return new LongSequence(allocator, maxDataSize);
  }

  private ByteBuffer encodeIntervals() {
    int encodedDataLimit =
        (maxDataSize * 3) / 4; // encoded data is in base64, growing by 4/3 in size
    int totalSequenceBufferSize = 0;
    int maxSequenceSize = 0;
    Set<Map.Entry<Long, LongSequence>> entrySet = new HashSet<>(threadSequences.entrySet());
    for (Map.Entry<Long, LongSequence> entry : entrySet) {
      LongSequence sequence = entry.getValue();
      maxSequenceSize = Math.max(maxSequenceSize, sequence.size());
      totalSequenceBufferSize += sequence.size();
    }

    IntervalEncoder encoder =
        new IntervalEncoder(
            startTimestampMillis,
            timeTicksProvider.frequency() / 1_000_000L,
            entrySet.size(),
            totalSequenceBufferSize);
    int threadCount = 0;
    /*
    The overall size limit can be hit when persisting the per-thread data.
    By randomizing the order of threads when persisting we can avoid the situation
    when certain threads will be consistently ignored.
     */
    List<Long> threadIds = new ArrayList<>(threadSequences.keySet());
    Collections.shuffle(threadIds);
    outer:
    for (Long threadId : threadIds) {
      threadCount++;
      IntervalEncoder.ThreadEncoder threadEncoder = encoder.startThread(threadId);
      LongSequence rawIntervals = threadSequences.get(threadId);
      synchronized (rawIntervals) {
        LongIterator iterator = pruneIntervals(rawIntervals);
        int sequenceIndex = 0;
        while (iterator.hasNext() && sequenceIndex++ < maxSequenceSize) {
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
}
