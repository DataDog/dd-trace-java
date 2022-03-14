package com.datadog.profiling.context;

import datadog.trace.api.RatelimitedLogger;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProfilerTracingContextTracker
    implements TracingContextTracker, TracingContextTracker.DelayedTracker {
  private static final Logger log = LoggerFactory.getLogger(ProfilerTracingContextTracker.class);

  static final int TRANSITION_STARTED = 0;
  static final int TRANSITION_MAYBE_FINISHED = 1;
  static final int TRANSITION_NONE = -1;
  static final int TRANSITION_FINISHED = 2;

  @FunctionalInterface
  public interface TimestampProvider {
    long timestamp();
  }

  private static final RatelimitedLogger warnlog =
      new RatelimitedLogger(
          LoggerFactory.getLogger(ProfilerTracingContextTracker.class), 30_000_000_000L);

  private static final long TRANSITION_MAYBE_FINISHED_MASK =
      (long) (TRANSITION_MAYBE_FINISHED) << 62;
  private static final long TRANSITION_FINISHED_MASK = (long) (TRANSITION_FINISHED) << 62;
  static final long TRANSITION_MASK = TRANSITION_FINISHED_MASK | TRANSITION_MAYBE_FINISHED_MASK;
  static final long TIMESTAMP_MASK = ~TRANSITION_MASK;

  private final long inactivityDelay;

  private final ConcurrentMap<Long, LongSequence> threadSequences = new ConcurrentHashMap<>(64);
  private final long startTimestamp;
  private final Allocator allocator;
  private final AtomicBoolean released = new AtomicBoolean();
  private final AgentSpan span;
  private final Set<IntervalBlobListener> blobListeners;
  private final TimestampProvider timestampProvider;
  private final IntervalSequencePruner sequencePruner;

  private final long initialThreadId;
  private final long initialTimestamp;
  private final AtomicBoolean initialized = new AtomicBoolean(false);

  private long lastTransitionTimestamp = -1;

  ProfilerTracingContextTracker(
      Allocator allocator,
      AgentSpan span,
      TimestampProvider timestampProvider,
      IntervalSequencePruner sequencePruner) {
    this(allocator, span, timestampProvider, sequencePruner, -1L);
  }

  ProfilerTracingContextTracker(
      Allocator allocator,
      AgentSpan span,
      TimestampProvider timestampProvider,
      IntervalSequencePruner sequencePruner,
      long inactivityDelay) {
    this.startTimestamp = timestampProvider.timestamp();
    this.timestampProvider = timestampProvider;
    this.sequencePruner = sequencePruner;
    this.span = span;
    this.allocator = allocator;
    this.blobListeners = new HashSet<>();
    this.initialTimestamp = this.startTimestamp + 1; // need at least one tick diff
    this.initialThreadId = Thread.currentThread().getId();
    this.lastTransitionTimestamp = System.nanoTime();
    this.inactivityDelay = inactivityDelay;
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
    long tsDiff = timestamp - startTimestamp;
    storeDelayedActivation();
    long masked = maskActivation(tsDiff);
    store(threadId, masked);
  }

  private long accessTimestamp() {
    long ts = timestampProvider.timestamp();
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
  public void deactivateContext(boolean maybe) {
    deactivateContext(Thread.currentThread().getId(), maybe);
  }

  private void deactivateContext(long threadId, boolean maybe) {
    deactivateContext(threadId, accessTimestamp(), maybe);
  }

  void deactivateContext(long threadId, long timestamp, boolean maybe) {
    long tsDiff = timestamp - startTimestamp;
    storeDelayedActivation();
    long masked = maskDeactivation(tsDiff, maybe);
    store(threadId, masked);
  }

  @Override
  public byte[] persist() {
    if (released.get()) {
      log.debug("Trying to persist already released tracker");
      return null;
    }

    ByteBuffer buffer = encodeIntervals();

    if (span != null) {
      AgentSpan root = span.getLocalRootSpan();
      for (IntervalBlobListener listener : blobListeners) {
        try {
          listener.onIntervalBlob(root, buffer.duplicate());
        } catch (OutOfMemoryError e) {
          throw e;
        } catch (Throwable t) {
          log.error("", t);
        }
      }
    }

    byte[] data = buffer.array();
    return Arrays.copyOf(data, buffer.limit());
  }

  LongIterator pruneIntervals(LongSequence sequence) {
    return sequencePruner.pruneIntervals(sequence, timestampProvider.timestamp());
  }

  @Override
  public boolean release() {
    if (released.compareAndSet(false, true)) {
      log.trace("Releasing tracing context for span {}", span);
      threadSequences.values().forEach(LongSequence::release);
      threadSequences.clear();
      return true;
    }
    return false;
  }

  @Override
  public int getVersion() {
    return 1;
  }

  @Override
  public DelayedTracker asDelayed() {
    return this;
  }

  @Override
  public void cleanup() {
    release();
  }

  @Override
  public long getDelay(TimeUnit unit) {
    return unit.convert(
        lastTransitionTimestamp + inactivityDelay - System.nanoTime(), TimeUnit.NANOSECONDS);
  }

  @Override
  public int compareTo(Delayed o) {
    if (o instanceof ProfilerTracingContextTracker) {
      ProfilerTracingContextTracker t = (ProfilerTracingContextTracker) o;
      return Long.compare(this.lastTransitionTimestamp, t.lastTransitionTimestamp);
    }
    return 0;
  }

  private void store(long threadId, long value) {
    if (released.get()) {
      return;
    }
    int added = 0;
    LongSequence sequence =
        threadSequences.computeIfAbsent(threadId, k -> new LongSequence(allocator));
    try {
      synchronized (sequence) {
        added = sequence.add(value);
      }
      if (added == -1) {
        warnlog.warn(
            "Attempting to add transition to already released context - losing tracing context data");
      } else if (added == 0) {
        warnlog.warn("Profiling Context Buffer is full - losing tracing context data");
      }
    } catch (Throwable t) {
      t.printStackTrace();
      log.error("", t);
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
        new IntervalEncoder(startTimestamp, threadSequences.size(), totalSequenceBufferSize);
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
            threadEncoder.recordInterval(maskedFrom, maskedTill);
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
      activateContext(initialThreadId, initialTimestamp);
    }
  }
}
