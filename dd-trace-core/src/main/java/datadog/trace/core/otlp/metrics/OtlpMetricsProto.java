package datadog.trace.core.otlp.metrics;

import static datadog.trace.api.config.OtlpConfig.Temporality.CUMULATIVE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.I64_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.LEN_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.VARINT_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.recordMessage;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeI64;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeInstrumentationScope;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeString;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeTag;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeVarInt;

import datadog.communication.serialization.GrowableBuffer;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpDoublePoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpHistogramPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpLongPoint;

/** Provides optimized writers for OpenTelemetry's "metrics.proto" wire protocol. */
public final class OtlpMetricsProto {
  private OtlpMetricsProto() {}

  private static final int AGGREGATION_TEMPORALITY_DELTA = 1;
  private static final int AGGREGATION_TEMPORALITY_CUMULATIVE = 2;

  private static final int AGGREGATION_TEMPORALITY =
      CUMULATIVE.equals(Config.get().getOtlpMetricsTemporalityPreference())
          ? AGGREGATION_TEMPORALITY_CUMULATIVE
          : AGGREGATION_TEMPORALITY_DELTA;

  /**
   * Records the first part of a scoped metrics message where we know its nested metric messages
   * will follow in one or more byte-arrays that add up to the given number of remaining bytes.
   */
  public static byte[] recordScopedMetricsMessage(
      GrowableBuffer buf, OtelInstrumentationScope scope, int remainingBytes) {

    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeInstrumentationScope(buf, scope);
    if (scope.getSchemaUrl() != null) {
      writeTag(buf, 3, LEN_WIRE_TYPE);
      writeString(buf, scope.getSchemaUrl().getUtf8Bytes());
    }

    return recordMessage(buf, 2, remainingBytes);
  }

  /**
   * Records the first part of a metric message where we know that its nested data point messages
   * will follow in one or more byte-arrays that add up to the given number of remaining bytes.
   */
  public static byte[] recordMetricMessage(
      GrowableBuffer buf, OtelInstrumentDescriptor descriptor, int remainingBytes) {

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
        writeVarInt(buf, remainingBytes);
        // gauges have no aggregation temporality
        break;
      case COUNTER:
      case OBSERVABLE_COUNTER:
        writeTag(buf, 7, LEN_WIRE_TYPE);
        writeVarInt(buf, remainingBytes + 4);
        writeTag(buf, 2, VARINT_WIRE_TYPE);
        writeVarInt(buf, AGGREGATION_TEMPORALITY);
        writeTag(buf, 3, VARINT_WIRE_TYPE);
        writeVarInt(buf, 1); // monotonic
        break;
      case UP_DOWN_COUNTER:
      case OBSERVABLE_UP_DOWN_COUNTER:
        writeTag(buf, 7, LEN_WIRE_TYPE);
        writeVarInt(buf, remainingBytes + 2);
        writeTag(buf, 2, VARINT_WIRE_TYPE);
        writeVarInt(buf, AGGREGATION_TEMPORALITY);
        break;
      case HISTOGRAM:
        writeTag(buf, 9, LEN_WIRE_TYPE);
        writeVarInt(buf, remainingBytes + 2);
        writeTag(buf, 2, VARINT_WIRE_TYPE);
        writeVarInt(buf, AGGREGATION_TEMPORALITY);
        break;
      default:
        throw new IllegalArgumentException("Unknown instrument type: " + descriptor.getType());
    }

    return recordMessage(buf, 2, remainingBytes);
  }

  /** Completes recording of a data point message and packs it into its own byte-array. */
  public static byte[] recordDataPointMessage(GrowableBuffer buf, OtlpDataPoint point) {
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
      for (double bucketCount : histogram.bucketCounts) {
        writeTag(buf, 6, I64_WIRE_TYPE);
        writeI64(buf, (long) bucketCount);
      }
      for (double bucketBoundary : histogram.bucketBoundaries) {
        writeTag(buf, 7, I64_WIRE_TYPE);
        writeI64(buf, bucketBoundary);
      }
    }

    return recordMessage(buf, 1);
  }
}
