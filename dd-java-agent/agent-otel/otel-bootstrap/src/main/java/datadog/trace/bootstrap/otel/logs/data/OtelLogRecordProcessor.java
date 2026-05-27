package datadog.trace.bootstrap.otel.logs.data;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor;
import datadog.trace.bootstrap.otlp.logs.OtlpLogRecord;
import datadog.trace.bootstrap.otlp.logs.OtlpLogsVisitor;
import datadog.trace.bootstrap.otlp.logs.OtlpScopedLogsVisitor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** Processes log records, grouping them by instrumentation scope. */
public final class OtelLogRecordProcessor {

  private static final Comparator<OtlpLogRecord> BY_SCOPE =
      Comparator.comparing(o -> o.instrumentationScope);

  private static final Map<ClassLoader, BiConsumer<Map<?, ?>, OtlpAttributeVisitor>>
      ATTRIBUTE_READERS = Collections.synchronizedMap(new WeakHashMap<>());

  public static final OtelLogRecordProcessor INSTANCE = new OtelLogRecordProcessor();

  private final int maxQueueSize = Config.get().getLogsOtelQueueSize();
  private final int maxBatchSize = Config.get().getLogsOtelBatchSize();

  private final Queue<OtlpLogRecord> queue = new ArrayBlockingQueue<>(maxQueueSize);

  private final BlockingQueue<Boolean> logsReady = new ArrayBlockingQueue<>(1);
  private volatile int logsNeeded = Integer.MAX_VALUE;

  public void addLog(OtlpLogRecord logRecord) {
    if (queue.offer(logRecord)) {
      // report when we have enough logs for the collector's needs
      if (queue.size() >= logsNeeded) {
        logsReady.offer(true);
      }
    }
  }

  public void waitForLogs(OtlpLogsVisitor visitor, int intervalMillis) {
    long nextExportNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(intervalMillis);
    List<OtlpLogRecord> batch = new ArrayList<>(maxBatchSize);
    int batchSize = 0;

    while (true) {

      // attempt to collect enough logs to complete the batch
      OtlpLogRecord record;
      while (batchSize < maxBatchSize && (record = queue.poll()) != null) {
        batch.add(record);
        batchSize++;
      }

      // bail out if we have enough logs, or the interval has expired
      long waitNanos;
      if (batchSize >= maxBatchSize || (waitNanos = nextExportNanos - System.nanoTime()) <= 0) {
        break;
      }

      logsNeeded = maxBatchSize - batchSize; // declare what we need and wait
      try {
        if (queue.isEmpty()) {
          logsReady.poll(waitNanos, TimeUnit.NANOSECONDS);
        }
      } catch (InterruptedException ignore) {
        // don't set interrupt flag as we might then busy-loop, just return batch as-is
        break;
      } finally {
        logsNeeded = Integer.MAX_VALUE;
      }
    }

    visitBatch(visitor, batch); // send what we have for this interval
  }

  private static void visitBatch(OtlpLogsVisitor visitor, List<OtlpLogRecord> batch) {
    batch.sort(BY_SCOPE);

    OtlpScopedLogsVisitor scopedVisitor = null;
    OtelInstrumentationScope currentScope = null;
    BiConsumer<Map<?, ?>, OtlpAttributeVisitor> attributesReader = null;
    ClassLoader attributesClassLoader = null;
    for (OtlpLogRecord logRecord : batch) {
      if (logRecord.instrumentationScope != currentScope) {
        currentScope = logRecord.instrumentationScope;
        scopedVisitor = visitor.visitScopedLogs(currentScope);
      }
      Map<?, ?> attributes = logRecord.attributes;
      if (!attributes.isEmpty()) {
        ClassLoader cl = getAttributesClassLoader(attributes);
        // avoid repeated lookups when attribute class-loader is same for all records
        if (attributesReader == null || cl != attributesClassLoader) {
          attributesReader = ATTRIBUTE_READERS.get(cl);
          attributesClassLoader = cl;
        }
        if (attributesReader != null) {
          attributesReader.accept(attributes, scopedVisitor);
        }
      }
      scopedVisitor.visitLogRecord(logRecord);
    }
  }

  private static ClassLoader getAttributesClassLoader(Map<?, ?> attributes) {
    // need to peek at the first key, as the map will be a JDK collection type
    return attributes.keySet().iterator().next().getClass().getClassLoader();
  }

  public static void registerAttributeReader(
      ClassLoader cl, BiConsumer<Map<?, ?>, OtlpAttributeVisitor> reader) {
    ATTRIBUTE_READERS.put(cl, reader);
  }
}
