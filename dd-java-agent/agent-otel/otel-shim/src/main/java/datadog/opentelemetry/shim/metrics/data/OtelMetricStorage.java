package datadog.opentelemetry.shim.metrics.data;

import datadog.opentelemetry.shim.metrics.OtelInstrumentDescriptor;
import datadog.opentelemetry.shim.metrics.export.OtelInstrumentVisitor;
import datadog.trace.api.Config;
import datadog.trace.api.config.OtlpConfig;
import datadog.trace.relocate.api.RatelimitedLogger;
import io.opentelemetry.api.common.Attributes;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stores and aggregates metrics data for a given instrument. */
public final class OtelMetricStorage {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelMetricStorage.class);
  private static final RatelimitedLogger RATELIMITED_LOGGER =
      new RatelimitedLogger(LOGGER, 5, TimeUnit.MINUTES);

  private static final int DEFAULT_MAX_CARDINALITY = 2_000;

  private static final Attributes CARDINALITY_OVERFLOW =
      Attributes.builder().put("otel.metric.overflow", true).build();

  private static final boolean RESET_ON_COLLECT =
      Config.get().getOtlpMetricsTemporalityPreference() == OtlpConfig.Temporality.DELTA;

  private final OtelInstrumentDescriptor descriptor;
  private final Function<Attributes, OtelAggregator> aggregatorSupplier;
  private volatile Recording currentRecording;

  // only used with DELTA temporality
  private Recording previousRecording;

  private OtelMetricStorage(
      OtelInstrumentDescriptor descriptor, Supplier<OtelAggregator> aggregatorSupplier) {
    this.descriptor = descriptor;
    this.aggregatorSupplier = unused -> aggregatorSupplier.get();
    this.currentRecording = new Recording();
    if (RESET_ON_COLLECT) {
      this.previousRecording = new Recording();
    }
  }

  public static OtelMetricStorage newDoubleSumStorage(OtelInstrumentDescriptor descriptor) {
    return new OtelMetricStorage(descriptor, OtelDoubleSum::new);
  }

  public static OtelMetricStorage newDoubleValueStorage(OtelInstrumentDescriptor descriptor) {
    return new OtelMetricStorage(descriptor, OtelDoubleValue::new);
  }

  public static OtelMetricStorage newLongSumStorage(OtelInstrumentDescriptor descriptor) {
    return new OtelMetricStorage(descriptor, OtelLongSum::new);
  }

  public static OtelMetricStorage newLongValueStorage(OtelInstrumentDescriptor descriptor) {
    return new OtelMetricStorage(descriptor, OtelLongValue::new);
  }

  public static OtelMetricStorage newHistogramStorage(
      OtelInstrumentDescriptor descriptor, List<Double> bucketBoundaries) {
    return new OtelMetricStorage(descriptor, () -> new OtelHistogramSketch(bucketBoundaries));
  }

  public String getInstrumentName() {
    return descriptor.getName();
  }

  public OtelInstrumentDescriptor getDescriptor() {
    return descriptor;
  }

  public void recordLong(long value, Attributes attributes) {
    Recording recording = acquireRecordingForWrite();
    try {
      aggregator(recording.aggregators, attributes).recordLong(value);
    } finally {
      releaseRecordingAfterWrite(recording);
    }
  }

  public void recordDouble(double value, Attributes attributes) {
    Recording recording = acquireRecordingForWrite();
    try {
      aggregator(recording.aggregators, attributes).recordDouble(value);
    } finally {
      releaseRecordingAfterWrite(recording);
    }
  }

  private OtelAggregator aggregator(
      Map<Attributes, OtelAggregator> aggregators, Attributes attributes) {
    Objects.requireNonNull(attributes, "attributes");
    OtelAggregator aggregator = aggregators.get(attributes);
    if (null != aggregator) {
      return aggregator;
    }
    if (aggregators.size() >= DEFAULT_MAX_CARDINALITY) {
      RATELIMITED_LOGGER.warn(
          "Instrument {} has exceeded the maximum allowed cardinality ({}).",
          descriptor.getName(),
          DEFAULT_MAX_CARDINALITY);
      attributes = CARDINALITY_OVERFLOW; // write data to overflow
    }
    return aggregators.computeIfAbsent(attributes, aggregatorSupplier);
  }

  public void collect(OtelInstrumentVisitor visitor) {
    if (RESET_ON_COLLECT) {
      doCollectAndReset(visitor);
    } else {
      doCollect(visitor);
    }
  }

  /** Collect data for CUMULATIVE temporality, keeping aggregators for future writes. */
  private void doCollect(OtelInstrumentVisitor visitor) {
    // no need to hold writers back if we are not resetting metrics on collect
    currentRecording.aggregators.forEach(
        (attributes, aggregator) -> {
          if (!aggregator.isEmpty()) {
            visitor.visitPoint(attributes, aggregator.collect());
          }
        });
  }

  /**
   * Collect data for DELTA temporality, resetting aggregators for future writes.
   *
   * <p>Each collect request toggles between two groups of aggregators: current / previous.
   */
  private void doCollectAndReset(OtelInstrumentVisitor visitor) {

    // capture _current_ recording for collection, its aggregators will be reset at the end
    final Recording recording = currentRecording;

    // publish fresh recording for new writers, using aggregators from _previous_ recording
    currentRecording = new Recording(previousRecording);

    // notify writers that the captured recording is about to be reset
    ACTIVITY.addAndGet(recording, RESET_PENDING);
    while (recording.activity > 1) {
      Thread.yield(); // other threads are still writing to this recording
    }

    Map<Attributes, OtelAggregator> aggregators = recording.aggregators;

    // avoid churn: only remove empty aggregators if we're over cardinality
    if (aggregators.size() >= DEFAULT_MAX_CARDINALITY) {
      aggregators.values().removeIf(OtelAggregator::isEmpty);
    }

    aggregators.forEach(
        (attributes, aggregator) -> {
          if (!aggregator.isEmpty()) {
            visitor.visitPoint(attributes, aggregator.collectAndReset());
          }
        });

    previousRecording = recording;
  }

  private Recording acquireRecordingForWrite() {
    if (RESET_ON_COLLECT) {
      // busy loop to limit impact on caller
      while (true) {
        final Recording recording = currentRecording;
        // atomically notify collector of write activity and check state
        if ((ACTIVITY.addAndGet(recording, WRITER) & RESET_PENDING) == 0) {
          return recording;
        } else {
          // reset pending: rollback and check again for a fresh recording
          ACTIVITY.addAndGet(recording, -WRITER);
        }
      }
    } else {
      return currentRecording;
    }
  }

  private void releaseRecordingAfterWrite(Recording recording) {
    if (RESET_ON_COLLECT) {
      ACTIVITY.addAndGet(recording, -WRITER);
    }
  }

  static final AtomicIntegerFieldUpdater<Recording> ACTIVITY =
      AtomicIntegerFieldUpdater.newUpdater(Recording.class, "activity");

  // first activity bit indicates if this recording is about to be reset
  private static final int RESET_PENDING = 1;

  // the other activity bits indicate how many threads are writing to it
  private static final int WRITER = 2;

  static final class Recording {
    final Map<Attributes, OtelAggregator> aggregators;

    transient volatile int activity;

    Recording() {
      this.aggregators = new ConcurrentHashMap<>();
    }

    Recording(Recording previous) {
      this.aggregators = previous.aggregators;
    }
  }
}
