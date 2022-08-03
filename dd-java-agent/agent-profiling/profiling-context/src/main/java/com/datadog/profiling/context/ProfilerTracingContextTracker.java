package com.datadog.profiling.context;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.function.ToIntFunction;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.relocate.api.RatelimitedLogger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProfilerTracingContextTracker implements TracingContextTracker {
  private static final Logger log = LoggerFactory.getLogger(ProfilerTracingContextTracker.class);
  private static final RatelimitedLogger warnLog = new RatelimitedLogger(log, 30, TimeUnit.SECONDS);

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

  private static final RatelimitedLogger warnlog =
      new RatelimitedLogger(
          LoggerFactory.getLogger(ProfilerTracingContextTracker.class), 30, TimeUnit.SECONDS);

  private static final long TRANSITION_MAYBE_FINISHED_MASK =
      (long) (TRANSITION_MAYBE_FINISHED) << 62;
  private static final long TRANSITION_FINISHED_MASK = (long) (TRANSITION_FINISHED) << 62;
  static final long TRANSITION_MASK = TRANSITION_FINISHED_MASK | TRANSITION_MAYBE_FINISHED_MASK;
  static final long TIMESTAMP_MASK = ~TRANSITION_MASK;

  private static final int SPAN_ACTIVATION_DATA_LIMIT;

  private final ThreadSequences threadSequences;
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

  private final ExpirationTracker.Expirable expirable;

  static {
    SPAN_ACTIVATION_DATA_LIMIT =
        ConfigProvider.getInstance()
            .getInteger(
                ProfilingConfig.PROFILING_TRACING_CONTEXT_MAX_SIZE,
                ProfilingConfig.PROFILING_TRACING_CONTEXT_MAX_SIZE_DEFAULT);
  }

  // @VisibleForTests
  ProfilerTracingContextTracker(
      Allocator allocator,
      AgentSpan span,
      TimeTicksProvider timeTicksProvider,
      IntervalSequencePruner sequencePruner,
      int maxDataSize) {
    this(
        allocator,
        new ThreadSequences(),
        span,
        timeTicksProvider,
        sequencePruner,
        ExpirationTracker.Expirable.EMPTY,
        maxDataSize);
  }

  ProfilerTracingContextTracker(
      Allocator allocator,
      ThreadSequences threadSequences,
      AgentSpan span,
      TimeTicksProvider timeTicksProvider,
      IntervalSequencePruner sequencePruner,
      ExpirationTracker.Expirable expirable) {
    this(allocator, threadSequences, span, timeTicksProvider, sequencePruner, expirable, SPAN_ACTIVATION_DATA_LIMIT);
  }

  ProfilerTracingContextTracker(
      Allocator allocator,
      ThreadSequences threadSequences,
      AgentSpan span,
      TimeTicksProvider timeTicksProvider,
      IntervalSequencePruner sequencePruner,
      ExpirationTracker.Expirable expirable,
      int maxDataSize) {
    this.startTimestampTicks = timeTicksProvider.ticks();
    this.startTimestampMillis = System.currentTimeMillis();
    this.timeTicksProvider = timeTicksProvider;
    this.sequencePruner = sequencePruner;
    this.span = span;
    this.allocator = allocator;
    this.threadSequences = threadSequences;
    this.initialThreadId = Thread.currentThread().getId();
    this.delayedActivationTimestamp = timeTicksProvider.ticks();
    this.expirable = expirable;
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
    if (expirable.hasExpiration()) {
      expirable.touch(System.nanoTime());
    }
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
      Set<LongMapEntry<LongSequence>> entrySet = null;
      try {
        if (released != 0) {
          // tracker was released without persisting the data
          return 0;
        }
        entrySet = threadSequences.snapshot();
        try {
          data = encodeIntervals(entrySet);
        } finally {
          // release the snapshot sequences as they are not used or tracked any more
          entrySet.forEach(e -> e.value.release());
        }
      } finally {
        // make sure the other threads do not stay blocked even if there is an exception thrown
        // unlock persist block
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
    try {
      return releaseThreadSequences();
    } finally {
      expirable.expire();
    }
  }

  boolean isReleased() {
    return released != 0;
  }

  private boolean releaseThreadSequences() {
    // the released flag needs to be set first such that it would prevent using the resourcese being
    // released from 'persist()' method
    if (releasedUpdater.compareAndSet(this, 0, 1)) {
      log.trace("Releasing tracing context for span {}", span);
      // now let's wait for any data being currently persisted
      // 'persist()' method guarantees that the 'persisted' value will become != EMPTY_DATA
      while (persisted == EMPTY_DATA) {
        Thread.yield();
      }
      // it is safe to cleanup the resources
      threadSequences.release();
      return true;
    }
    return false;
  }

  @Override
  public int getVersion() {
    return 1;
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
  @SuppressWarnings("unchecked")
  private ByteBuffer encodeIntervals(Set<LongMapEntry<LongSequence>> entrySet) {
    int encodedDataLimit =
        (maxDataSize * 3) / 4; // encoded data is in base64, growing by 4/3 in size
    int totalSequenceBufferSize = 0;
    LongMapEntry<LongSequence>[] entries = entrySet.toArray(new LongMapEntry[0]);
    for (LongMapEntry<LongSequence> entry : entries) {
      totalSequenceBufferSize += entry.value.captureSize();
    }

    IntervalEncoder encoder =
        new IntervalEncoder(
            startTimestampMillis,
            timeTicksProvider.frequency() / 1_000_000L,
            entries.length,
            totalSequenceBufferSize);
    int threadCount = 0;
    outer:
    for (LongMapEntry<LongSequence> entry : entries) {
      LongSequence rawIntervals = entry.value;
      if (rawIntervals == null) {
        // the tracker was released due to expiration
        warnlog.warn("Context interval sequence for thread id {} was not captured", entry.key);
        continue;
      }
      int sequenceSize = rawIntervals.getCapturedSize();

      if (sequenceSize == -1) {
        // this should never happen given the assumption under which this method is executed
        // but - just in case, log a warning and skip the sequence
        warnlog.warn("Context interval sequence for thread id {} was not captured", entry.key);
        continue;
      }
      IntervalEncoder.ThreadEncoder threadEncoder = encoder.startThread(entry.key);
      threadCount++;

      /*
      The only potential contention here will be between the tracked thread and the context summarization
      when the trace and span is being serialized. At that moment, however, the possibility of code related
      to that span still being executed in some other thread is pretty low. Therefore, most of the time the lock
      will be uncontented.
       */
      //noinspection SynchronizationOnLocalVariableOrMethodParameter
      synchronized (rawIntervals) {
        LongIterator iterator = pruneIntervals(rawIntervals);
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

  ExpirationTracker.Expirable getExpirable() {
    return expirable;
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
