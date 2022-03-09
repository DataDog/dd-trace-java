package com.datadog.profiling.context;

import datadog.trace.api.RatelimitedLogger;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TracingContextTrackerImpl implements TracingContextTracker {
  private static final Logger log = LoggerFactory.getLogger(TracingContextTrackerImpl.class);

  @FunctionalInterface
  public interface TimestampProvider {
    long timestamp();
  }

  private static final RatelimitedLogger warnlog =
      new RatelimitedLogger(
          LoggerFactory.getLogger(TracingContextTrackerImpl.class), 30_000_000_000L);

  private static final int TRANSITION_STARTED = 0;
  private static final int TRANSITION_MAYBE_FINISHED = 1;
  private static final int TRANSITION_NONE = -1;
  private static final int TRANSITION_FINISHED = 2;

  private static final long TRANSITION_MAYBE_FINISHED_MASK = (long)(TRANSITION_MAYBE_FINISHED) << 62;
  private static final long TRANSITION_FINISHED_MASK = (long)(TRANSITION_FINISHED) << 62;
  static final long TRANSITION_MASK = TRANSITION_FINISHED_MASK | TRANSITION_MAYBE_FINISHED_MASK;
  static final long TIMESTAMP_MASK = ~TRANSITION_MASK;

  private static final MethodHandle TIMESTAMP_MH;

  static {
    MethodHandle mh = null;
    try {
      Class<?> clz =
          TracingContextTrackerImpl.class.getClassLoader().loadClass("jdk.jfr.internal.JVM");
      mh = MethodHandles.lookup().findStatic(clz, "counterTime", MethodType.methodType(long.class));
    } catch (Throwable t) {
      log.error("Failed to initialize JFR timestamp access", t);
    }
    TIMESTAMP_MH = mh;
  }

  private static long timestamp() {
    if (TIMESTAMP_MH == null) {
      return System.nanoTime();
    }

    try {
      return (long) TIMESTAMP_MH.invokeExact();
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  private final ConcurrentMap<Long, LongSequence> threadSequences = new ConcurrentHashMap<>(64);
  private final long timestamp;
  private final Allocator allocator;
  private final AtomicBoolean released = new AtomicBoolean();
  private final AgentSpan span;
  private final Set<IntervalBlobListener> blobListeners;
  private final TimestampProvider timestampProvider;
  private final IntervalSequencePruner sequencePruner;

  TracingContextTrackerImpl(
      Allocator allocator, AgentSpan span, IntervalSequencePruner sequencePruner) {
    this(allocator, span, TracingContextTrackerImpl::timestamp, sequencePruner);
  }

  TracingContextTrackerImpl(
      Allocator allocator, AgentSpan span, TimestampProvider timestampProvider, IntervalSequencePruner sequencePruner) {
    this.timestamp = timestampProvider.timestamp();
    this.timestampProvider = timestampProvider;
    this.sequencePruner = sequencePruner;
    this.span = span;
    this.allocator = allocator;
    this.blobListeners = new HashSet<>();
  }

  void setBlobListeners(Set<IntervalBlobListener> blobListeners) {
    this.blobListeners.addAll(blobListeners);
  }

  @Override
  public void activateContext() {
    activateContext(Thread.currentThread().getId());
  }

  void activateContext(long threadId) {
    long ts = timestampProvider.timestamp() - timestamp;
    long masked = maskActivation(ts);
    store(threadId, masked);
  }

  static long maskActivation(long value) {
    return value & TIMESTAMP_MASK;
  }

  static long maskDeactivation(long value, boolean maybe) {
    return (value & TIMESTAMP_MASK) | (maybe ? TRANSITION_MAYBE_FINISHED_MASK : TRANSITION_FINISHED_MASK);
  }

  @Override
  public void deactivateContext(boolean maybe) {
    deactivateContext(Thread.currentThread().getId(), maybe);
  }

  void deactivateContext(long threadId, boolean maybe) {
    long ts = timestampProvider.timestamp() - timestamp;
    long masked = maskDeactivation(ts, maybe);
    store(threadId, masked);
  }

  @Override
  public byte[] persist() {
    if (released.get()) {
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

  @Override
  public byte[] persistAndRelease() {
    try {
      return persist();
    } finally {
      release();
    }
  }

  LongIterator pruneIntervals(LongSequence sequence) {
    return sequencePruner.pruneIntervals(sequence, timestampProvider.timestamp());
  }

  @Override
  public void release() {
    if (released.compareAndSet(false, true)) {
      threadSequences.values().forEach(LongSequence::release);
      threadSequences.clear();
    }
  }

  @Override
  public int getVersion() {
    return 1;
  }

  private boolean store(long threadId, long value) {
    int added = 0;
    LongSequence sequence =
        threadSequences.computeIfAbsent(threadId, k -> new LongSequence(allocator));
    try {
      synchronized (sequence) {
        added = sequence.add(value);
      }
      if (added == -1) {
        warnlog.warn("Attempting to add transition to already released context");
      } else if (added == 0) {
        warnlog.warn("Profiling Context Buffer is full - losing data");
      }
    } catch (Throwable t) {
      t.printStackTrace();
      log.error("", t);
    }
    return added > 0;
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

    IntervalEncoder encoder = new IntervalEncoder(timestamp, threadSequences.size(), totalSequenceBufferSize);
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
}
