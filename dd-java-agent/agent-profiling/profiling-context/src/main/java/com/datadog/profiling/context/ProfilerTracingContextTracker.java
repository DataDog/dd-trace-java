package com.datadog.profiling.context;

import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.relocate.api.RatelimitedLogger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProfilerTracingContextTracker implements TracingContextTracker {
  private static final Logger log = LoggerFactory.getLogger(ProfilerTracingContextTracker.class);

  private static final byte[] EMPTY_DATA = new byte[0];

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

  private final long inactivityDelay;

  private final ConcurrentMap<Long, LongSequence> threadSequences = new ConcurrentHashMap<>(64);
  private final long startTimestamp;
  private final long delayedActivationTimestamp;
  private final Allocator allocator;
  private final AtomicBoolean released = new AtomicBoolean();
  private final AgentSpan span;
  private final Set<IntervalBlobListener> blobListeners;
  private final TimeTicksProvider timeTicksProvider;
  private final IntervalSequencePruner sequencePruner;

  private final long initialThreadId;
  private final AtomicBoolean initialized = new AtomicBoolean(false);
  private final AtomicReference<byte[]> persisted = new AtomicReference<>(null);

  private long lastTransitionTimestamp = -1;

  private volatile DelayedTrackerImpl delayedTrackerRef = null;

  ProfilerTracingContextTracker(
      Allocator allocator,
      AgentSpan span,
      TimeTicksProvider timeTicksProvider,
      IntervalSequencePruner sequencePruner) {
    this(allocator, span, timeTicksProvider, sequencePruner, -1L);
  }

  ProfilerTracingContextTracker(
      Allocator allocator,
      AgentSpan span,
      TimeTicksProvider timeTicksProvider,
      IntervalSequencePruner sequencePruner,
      long inactivityDelay) {
    this.startTimestamp = timeTicksProvider.ticks();
    this.timeTicksProvider = timeTicksProvider;
    this.sequencePruner = sequencePruner;
    this.span = span;
    this.allocator = allocator;
    this.blobListeners = new HashSet<>();
    this.initialThreadId = Thread.currentThread().getId();
    this.lastTransitionTimestamp = System.nanoTime();
    this.inactivityDelay = inactivityDelay;
    this.delayedActivationTimestamp = timeTicksProvider.ticks();
  }

  void setBlobListeners(Set<IntervalBlobListener> blobListeners) {
    this.blobListeners.addAll(blobListeners);
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
    long tsDiff = timestamp - startTimestamp;
    long masked = maskActivation(tsDiff);
    store(threadId, masked);
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
    long tsDiff = timestamp - startTimestamp;
    long masked = maskDeactivation(tsDiff, maybe);
    store(threadId, masked);
  }

  @Override
  public byte[] persist() {
    if (released.get()) {
      log.debug("Trying to persist already released tracker");
      return null;
    }

    byte[] data = null;
    if (persisted.compareAndSet(null, EMPTY_DATA)) {
      try {
        ByteBuffer buffer = encodeIntervals();

        if (span != null) {
          for (IntervalBlobListener listener : blobListeners) {
            try {
              ByteBuffer duplicated = buffer.duplicate();
              listener.onIntervalBlob(span, duplicated);
            } catch (OutOfMemoryError e) {
              throw e;
            } catch (Throwable t) {
              warnlog.warn(
                  "Error while dispatching context blob to {}", listener.getClass().getName(), t);
            }
          }
        }

        data = buffer.array();
        data = Arrays.copyOf(data, buffer.limit());
      } finally {
        // make sure the other threads do not stay blocked even if there is an exception thrown
        persisted.compareAndSet(EMPTY_DATA, data);
      }
    } else {
      // busy wait for the data to become available
      while ((data = persisted.get()) == EMPTY_DATA) {
        Thread.yield();
      }
    }
    return data;
  }

  LongIterator pruneIntervals(LongSequence sequence) {
    return sequencePruner.pruneIntervals(sequence, timeTicksProvider.ticks() - startTimestamp);
  }

  @Override
  public boolean release() {
    boolean result = releaseThreadSequences();
    releaseDelayed();
    return result;
  }

  private boolean releaseThreadSequences() {
    if (released.compareAndSet(false, true)) {
      log.trace("Releasing tracing context for span {}", span);
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

  private void store(long threadId, long value) {
    if (released.get()) {
      return;
    }
    int added = 0;
    LongSequence sequence =
        threadSequences.computeIfAbsent(threadId, k -> new LongSequence(allocator));
    synchronized (sequence) {
      added = sequence.add(value);
    }
    if (added == -1) {
      warnlog.warn(
          "Attempting to add transition to already released context - losing tracing context data");
    } else if (added == 0) {
      warnlog.warn("Profiling Context Buffer is full - losing tracing context data");
    }
  }

  private ByteBuffer encodeIntervals() {
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
            startTimestamp,
            timeTicksProvider.frequency() / 1_000_000L,
            threadSequences.size(),
            totalSequenceBufferSize);
    for (Map.Entry<Long, LongSequence> entry : threadSequences.entrySet()) {
      long threadId = entry.getKey();
      IntervalEncoder.ThreadEncoder threadEncoder = encoder.startThread(threadId);
      LongSequence rawIntervals = entry.getValue();
      synchronized (rawIntervals) {
        LongIterator iterator = pruneIntervals(entry.getValue());
        int sequenceIndex = 0;
        while (iterator.hasNext() && sequenceIndex++ < maxSequenceSize) {
          long from = iterator.next();
          long maskedFrom = (from & TIMESTAMP_MASK);
          if (iterator.hasNext()) {
            long till = iterator.next();
            long maskedTill = (till & TIMESTAMP_MASK);
            if (maskedTill > maskedFrom) {
              threadEncoder.recordInterval(maskedFrom, maskedTill);
            }
          }
        }
      }
      threadEncoder.finish();
    }
    ByteBuffer buffer = encoder.finish();
    return buffer;
  }

  private void storeDelayedActivation() {
    if (initialized.compareAndSet(false, true)) {
      log.trace("Storing delayed activation for span {}", span);
      activateContext(initialThreadId, delayedActivationTimestamp);
    }
  }
}
