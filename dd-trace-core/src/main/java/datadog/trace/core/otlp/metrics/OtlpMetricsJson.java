package datadog.trace.core.otlp.metrics;

import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.GAUGE;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.OBSERVABLE_GAUGE;
import static datadog.trace.core.otlp.common.OtlpCommonJson.writeDouble;
import static datadog.trace.core.otlp.metrics.OtlpMetricsTemporality.COUNTER_TEMPORALITY;
import static datadog.trace.core.otlp.metrics.OtlpMetricsTemporality.HISTOGRAM_TEMPORALITY;
import static datadog.trace.core.otlp.metrics.OtlpMetricsTemporality.OBSERVABLE_COUNTER_TEMPORALITY;
import static datadog.trace.core.otlp.metrics.OtlpMetricsTemporality.TEMPORALITY_CUMULATIVE;
import static datadog.trace.core.otlp.metrics.OtlpMetricsTemporality.TEMPORALITY_DELTA;

import datadog.json.JsonWriter;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpDoublePoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpHistogramPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpLongPoint;

/** Provides writers for OpenTelemetry's "metrics.proto" JSON encoding. */
public final class OtlpMetricsJson {
  private OtlpMetricsJson() {}

  /** Opens a {@code Metric} JSON object, up to and including its {@code dataPoints} array. */
  public static void openMetric(JsonWriter writer, OtelInstrumentDescriptor descriptor) {
    openMetric(writer, descriptor, false);
  }

  /**
   * Opens a {@code Metric} JSON object, up to and including its {@code dataPoints} array. When
   * {@code forceDelta} is {@code true}, a histogram is encoded as DELTA regardless of the
   * configured temporality preference (for sources whose data points are inherently per-interval
   * deltas).
   */
  public static void openMetric(
      JsonWriter writer, OtelInstrumentDescriptor descriptor, boolean forceDelta) {
    writer.beginObject();
    writer.name("name").value(descriptor.getName().toString());
    if (descriptor.getDescription() != null) {
      writer.name("description").value(descriptor.getDescription().toString());
    }
    if (descriptor.getUnit() != null) {
      writer.name("unit").value(descriptor.getUnit().toString());
    }

    switch (descriptor.getType()) {
      case GAUGE:
      case OBSERVABLE_GAUGE:
        writer.name("gauge").beginObject();
        // gauges have no aggregation temporality
        break;
      case COUNTER:
        writer.name("sum").beginObject();
        writer.name("aggregationTemporality").value(COUNTER_TEMPORALITY);
        writer.name("isMonotonic").value(true);
        break;
      case OBSERVABLE_COUNTER:
        writer.name("sum").beginObject();
        writer.name("aggregationTemporality").value(OBSERVABLE_COUNTER_TEMPORALITY);
        writer.name("isMonotonic").value(true);
        break;
      case UP_DOWN_COUNTER:
      case OBSERVABLE_UP_DOWN_COUNTER:
        writer.name("sum").beginObject();
        // up/down counters are always cumulative
        writer.name("aggregationTemporality").value(TEMPORALITY_CUMULATIVE);
        writer.name("isMonotonic").value(false);
        break;
      case HISTOGRAM:
        writer.name("histogram").beginObject();
        writer
            .name("aggregationTemporality")
            .value(forceDelta ? TEMPORALITY_DELTA : HISTOGRAM_TEMPORALITY);
        break;
      default:
        throw new IllegalArgumentException("Unknown instrument type: " + descriptor.getType());
    }

    writer.name("dataPoints").beginArray();
  }

  /** Closes a {@code Metric} JSON object previously opened by {@link #openMetric}. */
  public static void closeMetric(JsonWriter writer) {
    writer.endArray(); // dataPoints
    writer.endObject(); // gauge|sum|histogram
    writer.endObject(); // metric
  }

  /** Writes a data point's value fields into the currently open data point object. */
  public static void writeDataPointValue(JsonWriter writer, OtlpDataPoint point) {
    if (point instanceof OtlpDoublePoint) {
      writer.name("asDouble");
      writeDouble(writer, ((OtlpDoublePoint) point).value);
    } else if (point instanceof OtlpLongPoint) {
      // int64 fields are encoded as decimal strings, per the OTLP JSON encoding spec
      writer.name("asInt").value(Long.toString(((OtlpLongPoint) point).value));
    } else { // must be a histogram point
      OtlpHistogramPoint histogram = (OtlpHistogramPoint) point;
      writer.name("count").value(Long.toString((long) histogram.count));
      writer.name("sum");
      writeDouble(writer, histogram.sum);
      writer.name("min");
      writeDouble(writer, histogram.min);
      writer.name("max");
      writeDouble(writer, histogram.max);
      if (!histogram.bucketCounts.isEmpty()) {
        boolean hasOverflow = false;
        writer.name("explicitBounds").beginArray();
        for (double bucketBoundary : histogram.bucketBoundaries) {
          if (!Double.isInfinite(bucketBoundary)) {
            writer.value(bucketBoundary);
          } else {
            hasOverflow = true; // don't write the overflow boundary
          }
        }
        writer.endArray();

        writer.name("bucketCounts").beginArray();
        for (double bucketCount : histogram.bucketCounts) {
          writer.value(Long.toString((long) bucketCount));
        }
        if (!hasOverflow) { // write one more count than boundaries
          writer.value("0");
        }
        writer.endArray();
      }
    }
  }
}
