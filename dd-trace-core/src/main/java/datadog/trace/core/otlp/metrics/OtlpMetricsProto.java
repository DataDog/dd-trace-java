package datadog.trace.core.otlp.metrics;

import static datadog.trace.core.otlp.common.OtlpCommonProto.I64_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.LEN_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.VARINT_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeI64;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeInstrumentationScope;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeString;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeTag;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeVarInt;
import static datadog.trace.core.otlp.metrics.OtlpMetricsTemporality.COUNTER_TEMPORALITY;
import static datadog.trace.core.otlp.metrics.OtlpMetricsTemporality.HISTOGRAM_TEMPORALITY;
import static datadog.trace.core.otlp.metrics.OtlpMetricsTemporality.OBSERVABLE_COUNTER_TEMPORALITY;
import static datadog.trace.core.otlp.metrics.OtlpMetricsTemporality.TEMPORALITY_CUMULATIVE;
import static datadog.trace.core.otlp.metrics.OtlpMetricsTemporality.TEMPORALITY_DELTA;

import datadog.communication.serialization.GrowableBuffer;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpDoublePoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpHistogramPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpLongPoint;
import datadog.trace.core.otlp.common.OtlpProtoBuffer;

/** Provides optimized writers for OpenTelemetry's "metrics.proto" wire protocol. */
public final class OtlpMetricsProto {
  private OtlpMetricsProto() {}

  /** Records a scoped metrics message after its nested metric messages have been recorded. */
  public static int recordScopedMetricsMessage(
      GrowableBuffer buf,
      OtelInstrumentationScope scope,
      int nestedMetricBytes,
      OtlpProtoBuffer protobuf) {

    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeInstrumentationScope(buf, scope);
    if (scope.getSchemaUrl() != null) {
      writeTag(buf, 3, LEN_WIRE_TYPE);
      writeString(buf, scope.getSchemaUrl().getUtf8Bytes());
    }

    return protobuf.recordMessage(buf, 2, nestedMetricBytes);
  }

  /** Records a metric message after its nested data point messages have been recorded. */
  public static int recordMetricMessage(
      GrowableBuffer buf,
      OtelInstrumentDescriptor descriptor,
      int nestedDataPointBytes,
      OtlpProtoBuffer protobuf) {
    return recordMetricMessage(buf, descriptor, nestedDataPointBytes, protobuf, false);
  }

  /**
   * Records a metric message after its nested data point messages have been recorded. When {@code
   * forceDelta} is {@code true}, a histogram is encoded as DELTA regardless of the configured
   * temporality preference (for sources whose data points are inherently per-interval deltas).
   */
  public static int recordMetricMessage(
      GrowableBuffer buf,
      OtelInstrumentDescriptor descriptor,
      int nestedDataPointBytes,
      OtlpProtoBuffer protobuf,
      boolean forceDelta) {

    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeString(buf, descriptor.getName().getUtf8Bytes());
    if (descriptor.getDescription() != null) {
      writeTag(buf, 2, LEN_WIRE_TYPE);
      writeString(buf, descriptor.getDescription().getUtf8Bytes());
    }
    if (descriptor.getUnit() != null) {
      writeTag(buf, 3, LEN_WIRE_TYPE);
      writeString(buf, descriptor.getUnit().getUtf8Bytes());
    }

    switch (descriptor.getType()) {
      case GAUGE:
      case OBSERVABLE_GAUGE:
        writeTag(buf, 5, LEN_WIRE_TYPE);
        writeVarInt(buf, nestedDataPointBytes);
        // gauges have no aggregation temporality
        break;
      case COUNTER:
        writeTag(buf, 7, LEN_WIRE_TYPE);
        writeVarInt(buf, nestedDataPointBytes + 4);
        writeTag(buf, 2, VARINT_WIRE_TYPE);
        writeVarInt(buf, COUNTER_TEMPORALITY);
        writeTag(buf, 3, VARINT_WIRE_TYPE);
        writeVarInt(buf, 1); // monotonic
        break;
      case OBSERVABLE_COUNTER:
        writeTag(buf, 7, LEN_WIRE_TYPE);
        writeVarInt(buf, nestedDataPointBytes + 4);
        writeTag(buf, 2, VARINT_WIRE_TYPE);
        writeVarInt(buf, OBSERVABLE_COUNTER_TEMPORALITY);
        writeTag(buf, 3, VARINT_WIRE_TYPE);
        writeVarInt(buf, 1); // monotonic
        break;
      case UP_DOWN_COUNTER:
      case OBSERVABLE_UP_DOWN_COUNTER:
        writeTag(buf, 7, LEN_WIRE_TYPE);
        writeVarInt(buf, nestedDataPointBytes + 2);
        writeTag(buf, 2, VARINT_WIRE_TYPE);
        // up/down counters are always cumulative
        writeVarInt(buf, TEMPORALITY_CUMULATIVE);
        break;
      case HISTOGRAM:
        writeTag(buf, 9, LEN_WIRE_TYPE);
        writeVarInt(buf, nestedDataPointBytes + 2);
        writeTag(buf, 2, VARINT_WIRE_TYPE);
        writeVarInt(buf, forceDelta ? TEMPORALITY_DELTA : HISTOGRAM_TEMPORALITY);
        break;
      default:
        throw new IllegalArgumentException("Unknown instrument type: " + descriptor.getType());
    }

    return protobuf.recordMessage(buf, 2, nestedDataPointBytes);
  }

  /** Records a data point message. */
  public static int recordDataPointMessage(
      GrowableBuffer buf, OtlpDataPoint point, OtlpProtoBuffer protobuf) {
    if (point instanceof OtlpDoublePoint) {
      writeTag(buf, 4, I64_WIRE_TYPE);
      writeI64(buf, ((OtlpDoublePoint) point).value);
    } else if (point instanceof OtlpLongPoint) {
      writeTag(buf, 6, I64_WIRE_TYPE);
      writeI64(buf, ((OtlpLongPoint) point).value);
    } else { // must be a histogram point
      OtlpHistogramPoint histogram = (OtlpHistogramPoint) point;
      writeTag(buf, 4, I64_WIRE_TYPE);
      writeI64(buf, (long) histogram.count);
      writeTag(buf, 5, I64_WIRE_TYPE);
      writeI64(buf, histogram.sum);
      writeTag(buf, 11, I64_WIRE_TYPE);
      writeI64(buf, histogram.min);
      writeTag(buf, 12, I64_WIRE_TYPE);
      writeI64(buf, histogram.max);
      if (!histogram.bucketCounts.isEmpty()) {
        boolean hasOverflow = false;
        for (double bucketBoundary : histogram.bucketBoundaries) {
          if (!Double.isInfinite(bucketBoundary)) {
            writeTag(buf, 7, I64_WIRE_TYPE);
            writeI64(buf, bucketBoundary);
          } else {
            hasOverflow = true; // don't write the overflow boundary
          }
        }
        for (double bucketCount : histogram.bucketCounts) {
          writeTag(buf, 6, I64_WIRE_TYPE);
          writeI64(buf, (long) bucketCount);
        }
        if (!hasOverflow) { // write one more count than boundaries
          writeTag(buf, 6, I64_WIRE_TYPE);
          writeI64(buf, 0L);
        }
      }
    }

    return protobuf.recordMessage(buf, 1);
  }
}
