package datadog.trace.common.metrics

import datadog.trace.api.Platform
import datadog.trace.api.WellKnownTags
import datadog.trace.bootstrap.instrumentation.api.Pair
import datadog.trace.test.util.DDSpecification
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import spock.lang.Requires

import java.nio.ByteBuffer

import static datadog.trace.api.Platform.isJavaVersionAtLeast
import static java.util.concurrent.TimeUnit.MILLISECONDS
import static java.util.concurrent.TimeUnit.SECONDS

@Requires({ isJavaVersionAtLeast(8) })
class SerializingMetricWriterTest extends DDSpecification {

  def "should produce correct message" () {
    setup:
    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis())
    long duration = SECONDS.toNanos(10)
    WellKnownTags wellKnownTags = new WellKnownTags("hostname", "env", "service", "version")
    ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content)
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink)

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
              Pair.of(new MetricKey("resource1", "service1", "operation1", "type", "", 0), new AggregateMetric().addHits(10).addErrors(1)),
              Pair.of(new MetricKey("resource2", "service2", "operation2", "type2", "dbtype", 200), new AggregateMetric().addHits(9).addErrors(1))
      ]
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
      assert mapSize == 4
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
        assert size == 11
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
        assert unpacker.unpackString() == "DBType"
        assert unpacker.unpackString() == key.getDbType() as String
        ++elementCount
        assert unpacker.unpackString() == "HTTPStatusCode"
        assert unpacker.unpackInt() == key.getHttpStatusCode()
        ++elementCount
        assert unpacker.unpackString() == "Hits"
        assert unpacker.unpackInt() == value.getHitCount()
        ++elementCount
        assert unpacker.unpackString() == "Errors"
        assert unpacker.unpackInt() == value.getErrorCount()
        ++elementCount
        assert unpacker.unpackString() == "Duration"
        assert unpacker.unpackLong() == value.getDuration()
        ++elementCount
        assert unpacker.unpackString() == "HitsSummary"
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
      if (Platform.isJavaVersionAtLeast(8)) {
        int length = unpacker.unpackBinaryHeader()
        assert length > 0
        unpacker.readPayload(length)
      } else {
        unpacker.skipValue()
      }
    }

    boolean validatedInput() {
      return validated
    }
  }
}
