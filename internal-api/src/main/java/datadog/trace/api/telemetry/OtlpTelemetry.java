package datadog.trace.api.telemetry;

import datadog.trace.api.Config;
import datadog.trace.api.config.OtlpConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/** Collects telemetry metrics for the OTLP trace, metrics, and log exporters. */
public class OtlpTelemetry implements MetricCollector<OtlpTelemetry.OtlpMetric> {
  private static final String NAMESPACE = "tracers";

  private static final OtlpTelemetry INSTANCE = new OtlpTelemetry();

  public static OtlpTelemetry getInstance() {
    return INSTANCE;
  }

  private final String[] tracesTags = tagsFor(Config.get().getOtlpTracesProtocol());
  private final String[] metricsTags = tagsFor(Config.get().getOtlpMetricsProtocol());
  private final String[] logsTags = tagsFor(Config.get().getOtlpLogsProtocol());

  private final String[] profilesTags = tagsFor(Config.get().getOtlpProfilesProtocol());
  private final ExportCounters profilesExport = new ExportCounters("profiles");

  // JFR→OTLP conversion timings, reported as avg/max gauges per flush window
  private final LongAdder profilesConversionNanosTotal = new LongAdder();
  private final LongAdder profilesConversionCount = new LongAdder();
  private final LongAccumulator profilesConversionNanosMax = new LongAccumulator(Long::max, 0);

  private final ExportCounters tracesExport = new ExportCounters("traces");
  private final ExportCounters metricsExport = new ExportCounters("metrics");
  private final LongAdder logRecords = new LongAdder();

  private final BlockingQueue<OtlpMetric> telemetryQueue = new ArrayBlockingQueue<>(RAW_QUEUE_SIZE);

  private OtlpTelemetry() {}

  public void onTracesExportAttempt() {
    tracesExport.attempts.increment();
  }

  public void onTracesExportComplete(boolean success) {
    tracesExport.complete(success);
  }

  public void onMetricsExportAttempt() {
    metricsExport.attempts.increment();
  }

  public void onProfilesExportAttempt() {
    profilesExport.attempts.increment();
  }

  public void onMetricsExportComplete(boolean success) {
    metricsExport.complete(success);
  }

  public void onProfilesExportComplete(boolean success) {
    profilesExport.complete(success);
  }

  /** Records the duration of a single JFR→OTLP profile conversion. */
  public void onProfilesConversion(long nanos) {
    profilesConversionNanosTotal.add(nanos);
    profilesConversionCount.increment();
    profilesConversionNanosMax.accumulate(nanos);
  }

  public void onLogRecordsSubmitted(long count) {
    if (count > 0) {
      logRecords.add(count);
    }
  }

  private static String[] tagsFor(OtlpConfig.Protocol protocol) {
    String protocolTag = protocol == OtlpConfig.Protocol.GRPC ? "grpc" : "http";
    String encodingTag = protocol == OtlpConfig.Protocol.HTTP_JSON ? "json" : "protobuf";
    return new String[] {"protocol:" + protocolTag, "encoding:" + encodingTag};
  }

  @Override
  public void prepareMetrics() {
    tracesExport.stageInto(telemetryQueue, tracesTags);
    metricsExport.stageInto(telemetryQueue, metricsTags);
    profilesExport.stageInto(telemetryQueue, profilesTags);
    long conversionCount = profilesConversionCount.sumThenReset();
    if (conversionCount > 0) {
      double avgNanos = profilesConversionNanosTotal.sumThenReset() / (double) conversionCount;
      long maxNanos = profilesConversionNanosMax.getThenReset();
      telemetryQueue.offer(
          new OtlpMetric(
              "otel.profiles_conversion_ms", "gauge", nanosToMillis(avgNanos), profilesTags));
      telemetryQueue.offer(
          new OtlpMetric(
              "otel.profiles_conversion_max_ms", "gauge", nanosToMillis(maxNanos), profilesTags));
    }
    long logRecordCount = logRecords.sumThenReset();
    if (logRecordCount > 0) {
      telemetryQueue.offer(new OtlpMetric("otel.log_records", logRecordCount, logsTags));
    }
  }

  private static double nanosToMillis(double nanos) {
    return nanos / 1_000_000.0;
  }

  @Override
  public Collection<OtlpMetric> drain() {
    if (telemetryQueue.isEmpty()) {
      return Collections.emptyList();
    }
    List<OtlpMetric> drained = new ArrayList<>(telemetryQueue.size());
    telemetryQueue.drainTo(drained);
    return drained;
  }

  /** Counters for a single signal's export attempts/successes/failures. */
  private static final class ExportCounters {
    final String attemptsMetric;
    final String successesMetric;
    final String failuresMetric;

    final LongAdder attempts = new LongAdder();
    final LongAdder successes = new LongAdder();
    final LongAdder failures = new LongAdder();

    ExportCounters(String signal) {
      this.attemptsMetric = "otel." + signal + "_export_attempts";
      this.successesMetric = "otel." + signal + "_export_successes";
      this.failuresMetric = "otel." + signal + "_export_failures";
    }

    void complete(boolean success) {
      (success ? successes : failures).increment();
    }

    void stageInto(BlockingQueue<OtlpMetric> out, String[] tags) {
      addIfNonZero(out, attemptsMetric, attempts, tags);
      addIfNonZero(out, successesMetric, successes, tags);
      addIfNonZero(out, failuresMetric, failures, tags);
    }

    private static void addIfNonZero(
        BlockingQueue<OtlpMetric> out, String metricName, LongAdder counter, String[] tags) {
      long value = counter.sumThenReset();
      if (value > 0) {
        out.offer(new OtlpMetric(metricName, value, tags));
      }
    }
  }

  public static class OtlpMetric extends MetricCollector.Metric {
    public OtlpMetric(String metricName, long value, String... tags) {
      this(metricName, "count", value, tags);
    }

    OtlpMetric(String metricName, String type, Number value, String... tags) {
      super(NAMESPACE, true, metricName, type, value, tags);
    }
  }
}
