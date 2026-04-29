package datadog.trace.bootstrap.otel.logs.data;

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
import java.util.Objects;
import java.util.Queue;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.BiConsumer;

/** Processes log records, grouping them by instrumentation scope. */
public final class OtelLogRecordProcessor {
  public static final OtelLogRecordProcessor INSTANCE = new OtelLogRecordProcessor();

  private static final Comparator<OtlpLogRecord> BY_SCOPE =
      Comparator.comparing(o -> o.instrumentationScope);

  private static final Map<ClassLoader, BiConsumer<Map<?, ?>, OtlpAttributeVisitor>>
      ATTRIBUTE_READERS = Collections.synchronizedMap(new WeakHashMap<>());

  private final Queue<OtlpLogRecord> queue = new ArrayBlockingQueue<>(2048);

  public void addLog(OtlpLogRecord logRecord) {
    queue.offer(logRecord);
  }

  public void collectLogs(OtlpLogsVisitor visitor) {
    OtlpScopedLogsVisitor scopedVisitor = null;
    OtelInstrumentationScope currentScope = null;
    BiConsumer<Map<?, ?>, OtlpAttributeVisitor> attributesReader = null;
    ClassLoader attributesClassLoader = null;
    for (OtlpLogRecord logRecord : batchByScope()) {
      if (logRecord.instrumentationScope != currentScope) {
        currentScope = logRecord.instrumentationScope;
        scopedVisitor = visitor.visitScopedLogs(currentScope);
      }
      Map<?, ?> attributes = logRecord.attributes;
      if (!attributes.isEmpty()) {
        ClassLoader cl = getAttributesClassLoader(attributes);
        // avoid repeated lookups when attribute class-loader is same for all records
        if (attributesReader == null || !Objects.equals(cl, attributesClassLoader)) {
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

  private List<OtlpLogRecord> batchByScope() {
    int batchSize = queue.size();
    List<OtlpLogRecord> batch = new ArrayList<>(batchSize);
    for (int i = 0; i < batchSize; i++) {
      OtlpLogRecord logRecord = queue.poll();
      if (logRecord != null) {
        batch.add(logRecord);
      } else {
        break;
      }
    }
    batch.sort(BY_SCOPE);
    return batch;
  }
}
