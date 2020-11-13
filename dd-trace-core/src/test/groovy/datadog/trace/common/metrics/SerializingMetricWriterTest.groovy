package datadog.trace.common.metrics

import datadog.trace.api.WellKnownTags
import datadog.trace.bootstrap.instrumentation.api.Pair
import datadog.trace.test.util.DDSpecification
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker

import java.nio.ByteBuffer

import static java.util.concurrent.TimeUnit.MILLISECONDS
import static java.util.concurrent.TimeUnit.SECONDS

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
        Pair.of(new MetricKey("resource1", "service1", "operation1", 0), new AggregateMetric().addHits(10).addErrors(1)),
        Pair.of(new MetricKey("resource2", "service2", "operation2", 200), new AggregateMetric().addHits(9).addErrors(1))
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
      assert mapSize == 3
      assert unpacker.unpackString() == "Hostname"
      assert unpacker.unpackString() == wellKnownTags.getHostname() as String
      assert unpacker.unpackString() == "Env"
      assert unpacker.unpackString() == wellKnownTags.getEnv() as String
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
        assert size == 9
        assert unpacker.unpackString() == "Name"
        assert unpacker.unpackString() == key.getOperationName() as String
        assert unpacker.unpackString() == "Env"
        assert unpacker.unpackString() == wellKnownTags.getEnv() as String
        assert unpacker.unpackString() == "Service"
        assert unpacker.unpackString() == key.getService() as String
        assert unpacker.unpackString() == "Resource"
        assert unpacker.unpackString() == key.getResource() as String
        assert unpacker.unpackString() == "Version"
        assert unpacker.unpackString() == wellKnownTags.getVersion() as String
        assert unpacker.unpackString() == "Hits"
        assert unpacker.unpackFloat() == value.getHitCount()
        assert unpacker.unpackString() == "Errors"
        assert unpacker.unpackFloat() == value.getErrorCount()
        assert unpacker.unpackString() == "Duration"
        assert unpacker.unpackDouble() == value.getDuration()
        assert unpacker.unpackString() == "TopLevel"
        unpacker.skipValue() // skip top level for now
      }
      validated = true
    }

    boolean validatedInput() {
      return validated
    }
  }
}
