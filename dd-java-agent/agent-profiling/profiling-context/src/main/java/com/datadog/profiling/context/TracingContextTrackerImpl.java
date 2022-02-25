package com.datadog.profiling.context;

import datadog.trace.api.RatelimitedLogger;
import datadog.trace.api.profiling.CustomEventAccess;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TracingContextTrackerImpl implements TracingContextTracker {
  private static final Logger log = LoggerFactory.getLogger(TracingContextTrackerImpl.class);
  private static final RatelimitedLogger warnlog = new RatelimitedLogger(LoggerFactory.getLogger(TracingContextTrackerImpl.class), 30_000_000_000L);

  private static final long TRANSITION_MASK = 0xC000000000000000L;
  private static final long TIMESTAMP_MASK = ~TRANSITION_MASK;
  private static final int EXT_BIT = 0x80;
  private static final long COMPRESSED_INT_MASK = -EXT_BIT;

  private static final AtomicInteger instanceCounter = new AtomicInteger(0);

  private static final MethodHandle TIMESTAMP_MH;

  static {
    MethodHandle mh = null;
    try {
      Class<?> clz =
          TracingContextTrackerImpl.class.getClassLoader().loadClass("jdk.jfr.internal.JVM");
      mh = MethodHandles.lookup().findStatic(clz, "counterTime", MethodType.methodType(long.class));
    } catch (Throwable t) {
      log.error("Failed to initialize JFR timestamp access");
    }
    TIMESTAMP_MH = mh;
  }

  private static long timestamp() {
    try {
      return (long) TIMESTAMP_MH.invokeExact();
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  private final ConcurrentMap<Long, LongSequence> threadSequences = new ConcurrentHashMap<>(64);
  private final ThreadLocal<LongSequence> localThreadBuffer =
      ThreadLocal.withInitial(this::initThreadBuffer);
  private final long timestamp;
  private final Allocator allocator;
  private final AtomicBoolean released = new AtomicBoolean();
  private final WeakReference<AgentSpan> spanRef;
  private final CustomEventAccess eventAccess;
  private ByteBuffer intervalBuffer =
      ByteBuffer.allocate(8 * 1024); // max 8k of interval data per context tracker (eg. a span)

  TracingContextTrackerImpl(Allocator allocator, long timestamp) {
    this(allocator, null, timestamp, CustomEventAccess.NULL);
  }

  public TracingContextTrackerImpl(
      Allocator allocator, AgentSpan span, CustomEventAccess eventAccess) {
    this(allocator, span, timestamp(), eventAccess);
  }

  private TracingContextTrackerImpl(
      Allocator allocator, AgentSpan span, long timestamp, CustomEventAccess eventAccess) {
    this.timestamp = timestamp;
    this.spanRef = new WeakReference<>(span);
    this.allocator = allocator;
    this.eventAccess = eventAccess;

    log.info("TracingContextTrackerImpl instances: {}", instanceCounter.incrementAndGet());
  }

  private LongSequence initThreadBuffer() {
    return threadSequences.computeIfAbsent(
        Thread.currentThread().getId(), k -> new LongSequence(allocator));
  }

  @Override
  public void activateContext() {
    if (TIMESTAMP_MH != null) {
      long ts = timestamp() - timestamp;
      store(ts & TIMESTAMP_MASK);
      //      log.info("activated[{}]", ts);
    }
  }

  // @VisibleForTesting
  boolean activateContext(long threadId, long timestamp) {
    return store(threadId, (timestamp - this.timestamp) & TIMESTAMP_MASK);
  }

  @Override
  public void deactivateContext(boolean maybe) {
    if (TIMESTAMP_MH != null) {
      long ts = timestamp() - timestamp;
      store((ts & TIMESTAMP_MASK) | (maybe ? 0x4000000000000000L : 0x8000000000000000L));
      //      log.info("deactivated[{}]: {}", ts, maybe);
    }
  }

  // @VisibleForTesting
  boolean deactivateContext(long threadId, long timestamp, boolean maybe) {
    return store(
        threadId,
        ((timestamp - this.timestamp) & TIMESTAMP_MASK)
            | (maybe ? 0x4000000000000000L : 0x8000000000000000L));
  }

  @Override
  public byte[] persist() {
    if (released.get()) {
      return null;
    }

    AgentSpan span = spanRef.get();
    long localRootSpanId = span != null ? span.getLocalRootSpan().getSpanId().toLong() : 0L;

    int totalSequenceBufferSize = 0;
    int maxSequenceBufferSize = 0;
    for (Map.Entry<Long, LongSequence> entry : threadSequences.entrySet()) {
      LongSequence sequence = entry.getValue();
      maxSequenceBufferSize = Math.max(maxSequenceBufferSize, sequence.size());
      totalSequenceBufferSize += sequence.size();
    }

    ByteBuffer dataChunkBuffer = ByteBuffer.allocate(maxSequenceBufferSize * 8 + 4);
    ByteBuffer groupVarintMapBuffer =
        ByteBuffer.allocate(align((int) (Math.ceil(totalSequenceBufferSize / 8d) * 3), 4));

    int dataChunkPointerOffset = 0;
    int groupVarintBitmapPointerOffset = 0;

    intervalBuffer.putInt(0); // pre-allocate a slot for pointer to interval data chunk
    putVarint(intervalBuffer, timestamp);
    putVarint(intervalBuffer, threadSequences.size()); // record the number of captured threads

    dataChunkBuffer.putInt(0); // pre-allocate a slot for pointer to group varint bitmap

    int maskPos = 0;
    int maskOffset = 0;
    for (Map.Entry<Long, LongSequence> entry : threadSequences.entrySet()) {
      long threadId = entry.getKey();
      long previousValue = 0;
      int intervals = 0;
      putVarint(intervalBuffer, threadId); // record the thread id

      IntervalCollapser traceContextEmitter =
          new IntervalCollapser(
              -1, timestamp, (s, d) -> emitTraceIntervalEvent(localRootSpanId, threadId, s, d));
      LongSequence rawIntervals = entry.getValue();
      synchronized (rawIntervals) {
        int size = rawIntervals.size();
        if (size > maxSequenceBufferSize) {
          warnlog.warn("Interval sequence has changed for thread {}. New size is {}", threadId, size);
        }
        LongIterator iterator = pruneIntervals(entry.getValue());
        while (iterator.hasNext()) {
          long value = iterator.next();
          if (value != 0) {
            long maskedValue = (value & TIMESTAMP_MASK);
            value = maskedValue - previousValue;

            traceContextEmitter.processDelta(value);
            previousValue = maskedValue;

            byte[] val = new byte[8];
            val[7] = (byte) (value & 0xff);
            val[6] = (byte) ((value >>> 8) & 0xff);
            val[5] = (byte) ((value >>> 16) & 0xff);
            val[4] = (byte) ((value >>> 24) & 0xff);
            val[3] = (byte) ((value >>> 32) & 0xff);
            val[2] = (byte) ((value >>> 40) & 0xff);
            val[1] = (byte) ((value >>> 48) & 0xff);
            val[0] = (byte) ((value >>> 56) & 0xff);

            int len = -1;
            for (int i = 0; i < 8; i++) {
              if (len == -1) {
                if (val[i] != 0 || i == 7) {
                  len = 0;
                  dataChunkBuffer.put(val[i]);
                }
              } else {
                dataChunkBuffer.put(val[i]);
                len++;
              }
            }

            groupVarintMapBuffer.position(maskPos);
            groupVarintMapBuffer.mark();
            int mask = groupVarintMapBuffer.getInt();

            // record the current length
            mask = mask | ((len & 0x7) << (29 - maskOffset));
            // and rewrite the mask
            groupVarintMapBuffer.reset();
            groupVarintMapBuffer.putInt(mask);
            if ((maskOffset += 3) > 21) {
              maskOffset = 0;
              maskPos += 3;
            }
            intervals++;
          }
        }
      }
      putVarint(
          intervalBuffer, intervals / 2); // record the number of intervals for the processed thread
    }
    dataChunkBuffer.putInt(
        groupVarintBitmapPointerOffset,
        dataChunkBuffer.position()); // record the pointer to the varint group bitmap
    intervalBuffer.putInt(
        dataChunkPointerOffset, intervalBuffer.position()); // record the pointer to the data chunk
    intervalBuffer.put(
        (ByteBuffer) dataChunkBuffer.flip()); // copy the data chunk to interval buffer
    intervalBuffer.put(
        (ByteBuffer)
            groupVarintMapBuffer.flip()); // copy the varint group bitmap to interval buffer

    byte[] data = intervalBuffer.array();
    return Arrays.copyOf(data, intervalBuffer.position());
  }

  private int align(int value, int alignment) {
    return ((value / alignment) + 1) * alignment;
  }

  public byte[] getContextBlob() {
    if (!released.get()) {
      byte[] data = intervalBuffer.array();
      return Arrays.copyOf(data, intervalBuffer.position());
    }
    return null;
  }

  private void putVarint(ByteBuffer buffer, long value) {
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) (value & 0xff));
      return;
    }
    buffer.put((byte) (value | EXT_BIT));
    value >>= 7;
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) value);
      return;
    }
    buffer.put((byte) (value | EXT_BIT));
    value >>= 7;
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) value);
      return;
    }
    buffer.put((byte) (value | EXT_BIT));
    value >>= 7;
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) value);
      return;
    }
    buffer.put((byte) (value | EXT_BIT));
    value >>= 7;
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) value);
      return;
    }
    buffer.put((byte) (value | EXT_BIT));
    value >>= 7;
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) value);
      return;
    }
    buffer.put((byte) (value | EXT_BIT));
    value >>= 7;
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) value);
      return;
    }
    buffer.put((byte) (value | EXT_BIT));
    value >>= 7;
    if ((value & COMPRESSED_INT_MASK) == 0) {
      buffer.put((byte) value);
      return;
    }
    buffer.put((byte) (value | EXT_BIT));
    buffer.put((byte) (value >> 7));
  }

  @Override
  public byte[] persistAndRelease() {
    try {
      return persist();
    } finally {
      release();
    }
  }

  private LongIterator pruneIntervals(LongSequence sequence) {
    int lastTransition = -1;
    int finishIndexStart = -1;
    int sequenceOffset = 0;
    LongIterator iterator = sequence.iterator();
    while (iterator.hasNext()) {
      long value = iterator.next();
      // transition: -1 = init, 0 = started, 1 = maybe finished, 2 = finished
      int transition = (int) ((value & TRANSITION_MASK) >>> 62);
      if (transition == 0) {
        if (lastTransition == 0) {
          // skip duplicated starts
          sequence.set(sequenceOffset, 0L);
        } else if (lastTransition == 1) {
          // skip 'maybe finished followed by started'
          sequence.set(sequenceOffset - 1, 0L);
          // also, ignore the start - instead just continue the previous interval
          sequence.set(sequenceOffset, 0L);
        } else if (lastTransition == 2) {
          if (finishIndexStart > -1) {
            int collapsedLength = sequenceOffset - finishIndexStart - 1;
            if (collapsedLength > 0) {
              for (int i = 0; i < collapsedLength; i++) {
                sequence.set(finishIndexStart + i, 0L);
              }
            }
            finishIndexStart = -1;
          }
        }
      } else if (transition == 1) {
        if (lastTransition == 1) {
          // skip duplicated maybe finished
          sequence.set(sequenceOffset - 1, 0L);
        } else if (lastTransition == 2) {
          // maybe finished followed by finished will turn into finished
          sequence.set(sequenceOffset, 0L);
          sequence.set(
              sequenceOffset - 1, (sequence.get(sequenceOffset - 1) & TIMESTAMP_MASK) | (2L << 62));
          finishIndexStart = -1;
        }
      } else if (transition == 2) {
        if (lastTransition == 1) {
          // maybe finished is duplicated by finished
          sequence.set(sequenceOffset - 1, 0L);
        } else if (lastTransition == 0) {
          // mark the start of 'finished' sequence
          finishIndexStart = sequenceOffset;
        }
      }
      lastTransition = transition;
      sequenceOffset++;
    }
    if (finishIndexStart > -1) {
      for (int i = finishIndexStart + 1; i < sequence.size(); i++) {
        sequence.set(i, 0L);
      }
    }
    return sequence.iterator();
  }

  @Override
  public void release() {
    if (released.compareAndSet(false, true)) {
      threadSequences.values().forEach(LongSequence::release);
      threadSequences.clear();
      intervalBuffer = null;
      instanceCounter.decrementAndGet();
    }
  }

  private boolean store(long threadId, long value) {
    LongSequence sequence =
        threadSequences.computeIfAbsent(threadId, k -> new LongSequence(allocator));
    return sequence.add(value);
  }

  private void store(long value) {
    LongSequence sequence = localThreadBuffer.get();
    try {
      boolean added = false;
      synchronized (sequence) {
        added = sequence.add(value);
      }
      if (!added) {
        warnlog.warn("Profiling Context Buffer is full. Losing data.");
      }
    } catch (Throwable t) {
      log.error("", t);
    }
  }

  private void emitTraceIntervalEvent(
      long localRootSpanId, long threadId, long startTime, long duration) {
    // localRootSpanId might have not been assigned yet; in that case just ignore
    if (localRootSpanId != 0) {
      eventAccess.emitTraceContextEvent(localRootSpanId, threadId, startTime, duration);
    }
  }
}
