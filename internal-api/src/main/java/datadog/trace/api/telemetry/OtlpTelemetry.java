package datadog.trace.api.telemetry;

import datadog.trace.api.Config;
import datadog.trace.api.config.OtlpConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

  private final ExportCounters tracesExport = new ExportCounters("traces");
  private final ExportCounters metricsExport = new ExportCounters("metrics");
  private final LongAdder logRecords = new LongAdder();

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

  public void onMetricsExportComplete(boolean success) {
    metricsExport.complete(success);
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
    // metrics are accumulated directly as they happen; nothing to prepare
  }

  @Override
  public Collection<OtlpMetric> drain() {
    List<OtlpMetric> drained = new ArrayList<>();
    tracesExport.drainInto(drained, tracesTags);
    metricsExport.drainInto(drained, metricsTags);
    long logRecordCount = logRecords.sumThenReset();
    if (logRecordCount > 0) {
      drained.add(new OtlpMetric("otel.log_records", logRecordCount, logsTags));
    }
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

    void drainInto(List<OtlpMetric> out, String[] tags) {
      addIfNonZero(out, attemptsMetric, attempts, tags);
      addIfNonZero(out, successesMetric, successes, tags);
      addIfNonZero(out, failuresMetric, failures, tags);
    }

    private static void addIfNonZero(
        List<OtlpMetric> out, String metricName, LongAdder counter, String[] tags) {
      long value = counter.sumThenReset();
      if (value > 0) {
        out.add(new OtlpMetric(metricName, value, tags));
      }
    }
  }

  public static class OtlpMetric extends MetricCollector.Metric {
    public OtlpMetric(String metricName, long value, String... tags) {
      super(NAMESPACE, true, metricName, "count", value, tags);
    }
  }
}
