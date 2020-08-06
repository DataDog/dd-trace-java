package datadog.trace.common.writer.ddagent

import datadog.trace.api.DDId
import datadog.trace.core.DDSpanData
import datadog.trace.core.TagsAndBaggageConsumer
import datadog.trace.core.serialization.msgpack.ByteBufferConsumer
import datadog.trace.core.serialization.msgpack.Packer
import datadog.trace.util.test.DDSpecification
import org.junit.Assert
import org.msgpack.core.MessageFormat
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker

import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

import static org.junit.Assert.assertEquals

import static org.msgpack.core.MessageFormat.*

class TraceMapperV05PayloadTest extends DDSpecification {

  def "test dictionary compressed traces written correctly" () {
    setup:
    List<List<DDSpanData>> traces = generateRandomTraces(traceCount)
    TraceMapperV0_5 traceMapper = new TraceMapperV0_5(100 << 10)
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper)
    Packer packer = new Packer(verifier, ByteBuffer.allocate(bufferSize))

    when:
    for (List<DDSpanData> trace : traces) {
      packer.format(trace, traceMapper)
    }
    packer.flush()

    then:
    verifier.verifyTracesConsumed()

    where:
    bufferSize    |   traceCount
    100 << 10     |       0
    100 << 10     |       1
    100 << 10     |       10
    100 << 10     |       100
  }



  private static final class PayloadVerifier implements ByteBufferConsumer, WritableByteChannel {

    private final List<List<DDSpanData>> expectedTraces
    private final TraceMapperV0_5 mapper
    private final ByteBuffer captured = ByteBuffer.allocate(200 << 10)

    private int position = 0

    private PayloadVerifier(List<List<DDSpanData>> traces, TraceMapperV0_5 mapper) {
      this.expectedTraces = traces
      this.mapper = mapper
    }

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      try {
        Payload payload = mapper.newPayload().withBody(messageCount, buffer)
        payload.writeTo(this)
        captured.flip()
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(captured)
        int header = unpacker.unpackArrayHeader()
        assertEquals(2, header)
        int dictionarySize = unpacker.unpackArrayHeader()
        String[] dictionary = new String[dictionarySize]
        for (int i = 0; i < dictionary.length; ++i) {
          dictionary[i] = unpacker.unpackString()
        }
        int traceCount = unpacker.unpackArrayHeader()
        for (int i = 0; i < traceCount; ++i) {
          List<DDSpanData> expectedTrace = expectedTraces.get(position++)
          int spanCount = unpacker.unpackArrayHeader()
          assertEquals(expectedTrace.size(), spanCount)
          for (int k = 0; k < spanCount; ++k) {
            DDSpanData expectedSpan = expectedTrace.get(k)
            int elementCount = unpacker.unpackArrayHeader()
            assertEquals(12, elementCount)
            String serviceName = dictionary[unpacker.unpackInt()]
            assertEqualsWithNullAsEmpty(expectedSpan.getServiceName(), serviceName)
            String operationName = dictionary[unpacker.unpackInt()]
            assertEqualsWithNullAsEmpty(expectedSpan.getOperationName(), operationName)
            String resourceName = dictionary[unpacker.unpackInt()]
            assertEqualsWithNullAsEmpty(expectedSpan.getResourceName(), resourceName)
            long traceId = unpacker.unpackLong()
            assertEquals(expectedSpan.getTraceId().toLong(), traceId)
            long spanId = unpacker.unpackLong()
            assertEquals(expectedSpan.getSpanId().toLong(), spanId)
            long parentId = unpacker.unpackLong()
            assertEquals(expectedSpan.getParentId().toLong(), parentId)
            long startTime = unpacker.unpackLong()
            assertEquals(expectedSpan.getStartTime(), startTime)
            long duration = unpacker.unpackLong()
            assertEquals(expectedSpan.getDurationNano(), duration)
            int error = unpacker.unpackInt()
            assertEquals(expectedSpan.getError(), error)
            int metaSize = unpacker.unpackMapHeader()
            HashMap<String, String> meta = new HashMap<>()
            for (int j = 0; j < metaSize; ++j) {
              meta.put(dictionary[unpacker.unpackInt()], dictionary[unpacker.unpackInt()])
            }
            for (Map.Entry<String, String> entry : meta.entrySet()) {
              Object tag = expectedSpan.getTags().get(entry.getKey())
              if (null != tag) {
                assertEquals(String.valueOf(tag), entry.getValue())
              } else {
                assertEquals(expectedSpan.getBaggage().get(entry.getKey()), entry.getValue())
              }
            }
            int metricsSize = unpacker.unpackMapHeader()
            HashMap<String, Number> metrics = new HashMap<>()
            for (int j = 0; j < metricsSize; ++j) {
              String key = dictionary[unpacker.unpackInt()]
              Number n = null
              MessageFormat format = unpacker.getNextFormat()
              switch (format) {
                case NEGFIXINT:
                case POSFIXINT:
                case INT8:
                case UINT8:
                case INT16:
                case UINT16:
                case INT32:
                case UINT32:
                  n = unpacker.unpackInt()
                  break
                case INT64:
                case UINT64:
                  n = unpacker.unpackLong()
                  break
                case FLOAT32:
                  n = unpacker.unpackFloat()
                  break
                case FLOAT64:
                  n = unpacker.unpackDouble()
                  break
                default:
                  Assert.fail("Unexpected type in metrics values: " + format)
              }
              metrics.put(key, n)
            }
            for (Map.Entry<String, Number> metric : metrics.entrySet()) {
              assertEquals(expectedSpan.getMetrics().get(metric.getKey()), metric.getValue())
            }
            String type = dictionary[unpacker.unpackInt()]
            assertEquals(expectedSpan.getType(), type)
          }
        }
      } catch (IOException e) {
        Assert.fail(e.getMessage())
      } finally {
        mapper.reset()
        captured.position(0)
        captured.limit(captured.capacity())
      }
    }

    @Override
    int write(ByteBuffer src) {
      captured.put(src)
      return src.position()
    }

    void verifyTracesConsumed() {
      assertEquals(expectedTraces.size(), position)
    }

    @Override
    boolean isOpen() {
      return true
    }

    @Override
    void close() {}
  }

  private static void assertEqualsWithNullAsEmpty(CharSequence expected, CharSequence actual) {
    if (null == expected) {
      assertEquals("", actual)
    } else {
      assertEquals(expected, actual)
    }
  }

  private static List<List<DDSpanData>> generateRandomTraces(int howMany) {
    List<List<DDSpanData>> traces = new ArrayList<>(howMany)
    for (int i = 0; i < howMany; ++i) {
      int traceSize = ThreadLocalRandom.current().nextInt(2, 20)
      traces.add(generateRandomTrace(traceSize))
    }
    return traces
  }

  private static List<DDSpanData> generateRandomTrace(int size) {
    List<DDSpanData> trace = new ArrayList<>(size)
    long traceId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)
    for (int i = 0; i < size; ++i) {
      trace.add(randomSpan(traceId))
    }
    return trace
  }

  private static DDSpanData randomSpan(long traceId) {
    Map<String, String> baggage = new HashMap<>()
    baggage.put("baggage-key", UUID.randomUUID().toString())
    Map<String, Object> tags = new HashMap<>()
    tags.put("tag.1", "foo")
    tags.put("tag.2", UUID.randomUUID())
    Map<String, Number> metrics = new HashMap<>()
    metrics.put("metric.1", ThreadLocalRandom.current().nextInt())
    return new PojoSpan(
      "service-" + ThreadLocalRandom.current().nextInt(10),
      "operation-" + ThreadLocalRandom.current().nextInt(100),
      "resource-" + ThreadLocalRandom.current().nextInt(100),
      DDId.from(traceId),
      DDId.generate(),
      DDId.ZERO,
      TimeUnit.MICROSECONDS.toMicros(System.currentTimeMillis()),
      ThreadLocalRandom.current().nextLong(500, 10_000_000),
      ThreadLocalRandom.current().nextInt(2),
      metrics,
      baggage,
      tags,
      "type-" + ThreadLocalRandom.current().nextInt(100))
  }

  static class PojoSpan implements DDSpanData {

    private final String serviceName
    private final String operationName
    private final CharSequence resourceName
    private final DDId traceId
    private final DDId spanId
    private final DDId parentId
    private final long start
    private final long duration
    private final int error
    private final Map<String, Number> metrics
    private final Map<String, String> baggage
    private final Map<String, Object> tags
    private final String type

    PojoSpan(
      String serviceName,
      String operationName,
      CharSequence resourceName,
      DDId traceId,
      DDId spanId,
      DDId parentId,
      long start,
      long duration,
      int error,
      Map<String, Number> metrics,
      Map<String, String> baggage,
      Map<String, Object> tags,
      String type) {
      this.serviceName = serviceName
      this.operationName = operationName
      this.resourceName = resourceName
      this.traceId = traceId
      this.spanId = spanId
      this.parentId = parentId
      this.start = start
      this.duration = duration
      this.error = error
      this.metrics = metrics
      this.baggage = baggage
      this.tags = tags
      this.type = type
    }

    @Override
    String getServiceName() {
      return serviceName
    }

    @Override
    String getOperationName() {
      return operationName
    }

    @Override
    CharSequence getResourceName() {
      return resourceName
    }

    @Override
    DDId getTraceId() {
      return traceId
    }

    @Override
    DDId getSpanId() {
      return spanId
    }

    @Override
    DDId getParentId() {
      return parentId
    }

    @Override
    long getStartTime() {
      return start
    }

    @Override
    long getDurationNano() {
      return duration
    }

    @Override
    int getError() {
      return error
    }

    @Override
    Map<String, Number> getMetrics() {
      return metrics
    }

    @Override
    Map<String, String> getBaggage() {
      return baggage
    }

    @Override
    Map<String, Object> getTags() {
      return tags
    }

    @Override
    String getType() {
      return type
    }

    @Override
    void processTagsAndBaggage(TagsAndBaggageConsumer consumer) {
      consumer.accept(tags, baggage)
    }
  }
}
