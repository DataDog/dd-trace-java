package datadog.trace.common.writer.ddagent


import datadog.trace.core.DDSpanData
import datadog.trace.core.serialization.msgpack.ByteBufferConsumer
import datadog.trace.core.serialization.msgpack.Packer
import datadog.trace.util.test.DDSpecification
import org.junit.Assert
import org.msgpack.core.MessageFormat
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker

import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

import static datadog.trace.common.writer.ddagent.TraceGenerator.generateRandomTraces
import static org.junit.Assert.assertEquals
import static org.msgpack.core.MessageFormat.FLOAT32
import static org.msgpack.core.MessageFormat.FLOAT64
import static org.msgpack.core.MessageFormat.INT16
import static org.msgpack.core.MessageFormat.INT32
import static org.msgpack.core.MessageFormat.INT64
import static org.msgpack.core.MessageFormat.INT8
import static org.msgpack.core.MessageFormat.NEGFIXINT
import static org.msgpack.core.MessageFormat.POSFIXINT
import static org.msgpack.core.MessageFormat.UINT16
import static org.msgpack.core.MessageFormat.UINT32
import static org.msgpack.core.MessageFormat.UINT64
import static org.msgpack.core.MessageFormat.UINT8

class TraceMapperV05PayloadTest extends DDSpecification {

  def "test dictionary compressed traces written correctly" () {
    setup:
    List<List<DDSpanData>> traces = generateRandomTraces(traceCount, lowCardinality)
    TraceMapperV0_5 traceMapper = new TraceMapperV0_5(dictionarySize)
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
    bufferSize    | dictionarySize |   traceCount   | lowCardinality
    10 << 10      |   10 << 10     |       0        | true
    10 << 10      |   10 << 10     |       1        | true
    10 << 10      |   10 << 10     |       10       | true
    10 << 10      |   10 << 10     |       100      | true
    10 << 10      |   10 << 10     |       10000    | true
    10 << 10      |   100 << 10    |       1        | true
    10 << 10      |   100 << 10    |       10       | true
    10 << 10      |   100 << 10    |       100      | true
    10 << 10      |   10 << 10     |       0        | false
    10 << 10      |   10 << 10     |       1        | false
    10 << 10      |   10 << 10     |       10       | false
    10 << 10      |   10 << 10     |       100      | false
    10 << 10      |   10 << 10     |       10000    | false
    10 << 10      |   100 << 10    |       1        | false
    10 << 10      |   100 << 10    |       10       | false
    10 << 10      |   100 << 10    |       100      | false
    100 << 10     |   10 << 10     |       0        | true
    100 << 10     |   10 << 10     |       1        | true
    100 << 10     |   10 << 10     |       10       | true
    100 << 10     |   10 << 10     |       100      | true
    100 << 10     |   10 << 10     |       10000    | true
    100 << 10     |   100 << 10    |       1        | true
    100 << 10     |   100 << 10    |       10       | true
    100 << 10     |   100 << 10    |       100      | true
    100 << 10     |   10 << 10     |       0        | false
    100 << 10     |   10 << 10     |       1        | false
    100 << 10     |   10 << 10     |       10       | false
    100 << 10     |   10 << 10     |       100      | false
    100 << 10     |   10 << 10     |       10000    | false
    100 << 10     |   100 << 10    |       1        | false
    100 << 10     |   100 << 10    |       10       | false
    100 << 10     |   100 << 10    |       100      | false
    100 << 10     |   100 << 10    |       10000    | false
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
      assertEquals(expected.toString(), actual.toString())
    }
  }
}
