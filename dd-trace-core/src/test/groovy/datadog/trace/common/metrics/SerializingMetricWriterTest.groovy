package datadog.trace.common.metrics

import datadog.trace.api.WellKnownTags
import datadog.trace.api.Pair
import datadog.trace.test.util.DDSpecification
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLongArray

import static java.util.concurrent.TimeUnit.MILLISECONDS
import static java.util.concurrent.TimeUnit.SECONDS

class SerializingMetricWriterTest extends DDSpecification {

  def "should produce correct message" () {
    setup:
    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis())
    long duration = SECONDS.toNanos(10)
    WellKnownTags wellKnownTags = new WellKnownTags("runtimeid", "hostname", "env", "service", "version","language")
    ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content)
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128)

    when:
    writer.startBucket(content.size(), startTime, duration)
    for (Pair<MetricKey, AggregateMetric> pair : content) {
      writer.add(pair.getLeft(), pair.getRight())
    }
    writer.finishBucket()

    then:
    sink.validatedInput()


    where:
    content << [
      [
        Pair.of(new MetricKey("resource1", "service1", "operation1", "type", 0, false), new AggregateMetric().recordDurations(10, new AtomicLongArray(1L))),
        Pair.of(new MetricKey("resource2", "service2", "operation2", "type2", 200, true), new AggregateMetric().recordDurations(9, new AtomicLongArray(1L)))
      ],
      (0..10000).collect({ i ->
        Pair.of(new MetricKey("resource" + i, "service" + i, "operation" + i, "type", 0, false), new AggregateMetric().recordDurations(10, new AtomicLongArray(1L)))
      })
    ]
  }


  class ValidatingSink implements Sink {

    private final WellKnownTags wellKnownTags
    private final long startTimeNanos
    private final long duration
    private boolean validated = false
    private List<Pair<MetricKey, AggregateMetric>> content

    ValidatingSink(WellKnownTags wellKnownTags, long startTimeNanos, long duration,
    List<Pair<MetricKey, AggregateMetric>> content) {
      this.wellKnownTags = wellKnownTags
      this.startTimeNanos = startTimeNanos
      this.duration = duration
      this.content = content
    }

    @Override
    void register(EventListener listener) {
    }

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer)
      int mapSize = unpacker.unpackMapHeader()
      assert mapSize == 6
      assert unpacker.unpackString() == "RuntimeId"
      assert unpacker.unpackString() == wellKnownTags.getRuntimeId() as String
      assert unpacker.unpackString() == "Seq"
      assert unpacker.unpackLong() == 0L
      assert unpacker.unpackString() == "Hostname"
      assert unpacker.unpackString() == wellKnownTags.getHostname() as String
      assert unpacker.unpackString() == "Env"
      assert unpacker.unpackString() == wellKnownTags.getEnv() as String
      assert unpacker.unpackString() == "Version"
      assert unpacker.unpackString() == wellKnownTags.getVersion() as String
      assert unpacker.unpackString() == "Stats"
      int outerLength = unpacker.unpackArrayHeader()
      assert outerLength == 1
      assert unpacker.unpackMapHeader() == 3
      assert unpacker.unpackString() == "Start"
      assert unpacker.unpackLong() == startTimeNanos
      assert unpacker.unpackString() == "Duration"
      assert unpacker.unpackLong() == duration
      assert unpacker.unpackString() == "Stats"
      int statCount = unpacker.unpackArrayHeader()
      assert statCount == content.size()
      for (Pair<MetricKey, AggregateMetric> pair : content) {
        MetricKey key = pair.getLeft()
        AggregateMetric value = pair.getRight()
        int size = unpacker.unpackMapHeader()
        assert size == 12
        int elementCount = 0
        assert unpacker.unpackString() == "Name"
        assert unpacker.unpackString() == key.getOperationName() as String
        ++elementCount
        assert unpacker.unpackString() == "Service"
        assert unpacker.unpackString() == key.getService() as String
        ++elementCount
        assert unpacker.unpackString() == "Resource"
        assert unpacker.unpackString() == key.getResource() as String
        ++elementCount
        assert unpacker.unpackString() == "Type"
        assert unpacker.unpackString() == key.getType() as String
        ++elementCount
        assert unpacker.unpackString() == "HTTPStatusCode"
        assert unpacker.unpackInt() == key.getHttpStatusCode()
        ++elementCount
        assert unpacker.unpackString() == "Synthetics"
        assert unpacker.unpackBoolean() == key.isSynthetics()
        ++elementCount
        assert unpacker.unpackString() == "Hits"
        assert unpacker.unpackInt() == value.getHitCount()
        ++elementCount
        assert unpacker.unpackString() == "Errors"
        assert unpacker.unpackInt() == value.getErrorCount()
        ++elementCount
        assert unpacker.unpackString() == "TopLevelHits"
        assert unpacker.unpackInt() == value.getTopLevelCount()
        ++elementCount
        assert unpacker.unpackString() == "Duration"
        assert unpacker.unpackLong() == value.getDuration()
        ++elementCount
        assert unpacker.unpackString() == "OkSummary"
        validateSketch(unpacker)
        ++elementCount
        assert unpacker.unpackString() == "ErrorSummary"
        validateSketch(unpacker)
        ++elementCount
        assert elementCount == size
      }
      validated = true
    }

    private void validateSketch(MessageUnpacker unpacker) {
      int length = unpacker.unpackBinaryHeader()
      assert length > 0
      unpacker.readPayload(length)
    }

    boolean validatedInput() {
      return validated
    }
  }
}
