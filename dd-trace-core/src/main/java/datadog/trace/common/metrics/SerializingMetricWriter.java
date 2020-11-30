package datadog.trace.common.metrics;

import static datadog.trace.core.serialization.WritableFormatter.Feature.RESIZEABLE;
import static datadog.trace.core.serialization.WritableFormatter.Feature.SINGLE_MESSAGE;
import static java.nio.charset.StandardCharsets.US_ASCII;

import datadog.trace.api.WellKnownTags;
import datadog.trace.core.serialization.WritableFormatter;
import datadog.trace.core.serialization.msgpack.MsgPackWriter;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class SerializingMetricWriter implements MetricWriter {

  private static final byte[] HOSTNAME = "Hostname".getBytes(US_ASCII);
  private static final byte[] NAME = "Name".getBytes(US_ASCII);
  private static final byte[] ENV = "Env".getBytes(US_ASCII);
  private static final byte[] SERVICE = "Service".getBytes(US_ASCII);
  private static final byte[] RESOURCE = "Resource".getBytes(US_ASCII);
  private static final byte[] VERSION = "Version".getBytes(US_ASCII);
  private static final byte[] HITS = "Hits".getBytes(US_ASCII);
  private static final byte[] ERRORS = "Errors".getBytes(US_ASCII);
  private static final byte[] DURATION = "Duration".getBytes(US_ASCII);
  private static final byte[] TYPE = "Type".getBytes(US_ASCII);
  private static final byte[] DBTYPE = "DBType".getBytes(US_ASCII);
  private static final byte[] HTTP_STATUS_CODE = "HTTPStatusCode".getBytes(US_ASCII);
  private static final byte[] START = "Start".getBytes(US_ASCII);
  private static final byte[] STATS = "Stats".getBytes(US_ASCII);
  private static final byte[] HITS_SUMMARY = "HitsSummary".getBytes(US_ASCII);
  private static final byte[] ERROR_SUMMARY = "ErrorSummary".getBytes(US_ASCII);

  private final WellKnownTags wellKnownTags;
  private final WritableFormatter writer;

  public SerializingMetricWriter(WellKnownTags wellKnownTags, Sink sink) {
    this.wellKnownTags = wellKnownTags;
    this.writer =
        new MsgPackWriter(
            // 64KB
            sink, ByteBuffer.allocate(64 << 10), EnumSet.of(RESIZEABLE, SINGLE_MESSAGE));
  }

  @Override
  public void startBucket(int metricCount, long start, long duration) {
    writer.startMap(4);

    writer.writeUTF8(HOSTNAME);
    writer.writeUTF8(wellKnownTags.getHostname());

    writer.writeUTF8(ENV);
    writer.writeUTF8(wellKnownTags.getEnv());

    writer.writeUTF8(VERSION);
    writer.writeUTF8(wellKnownTags.getVersion());

    writer.writeUTF8(STATS);
    writer.startArray(1);

    writer.startMap(3);

    writer.writeUTF8(START);
    writer.writeLong(start);

    writer.writeUTF8(DURATION);
    writer.writeLong(duration);

    writer.writeUTF8(STATS);
    writer.startArray(metricCount);
  }

  @Override
  public void add(MetricKey key, AggregateMetric aggregate) {
    writer.startMap(11);

    writer.writeUTF8(NAME);
    writer.writeUTF8(key.getOperationName());

    writer.writeUTF8(SERVICE);
    writer.writeUTF8(key.getService());

    writer.writeUTF8(RESOURCE);
    writer.writeUTF8(key.getResource());

    writer.writeUTF8(TYPE);
    writer.writeUTF8(key.getType());

    writer.writeUTF8(DBTYPE);
    writer.writeUTF8(key.getDbType());

    writer.writeUTF8(HTTP_STATUS_CODE);
    writer.writeInt(key.getHttpStatusCode());

    writer.writeUTF8(HITS);
    writer.writeInt(aggregate.getHitCount());

    writer.writeUTF8(ERRORS);
    writer.writeInt(aggregate.getErrorCount());

    writer.writeUTF8(DURATION);
    writer.writeLong(aggregate.getDuration());

    writer.writeUTF8(HITS_SUMMARY);
    writer.writeBinary(aggregate.getHitLatencies());

    writer.writeUTF8(ERROR_SUMMARY);
    writer.writeBinary(aggregate.getErrorLatencies());
  }

  @Override
  public void finishBucket() {
    writer.flush();
  }
}
