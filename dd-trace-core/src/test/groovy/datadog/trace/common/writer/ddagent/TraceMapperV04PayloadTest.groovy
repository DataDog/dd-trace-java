package datadog.trace.common.writer.ddagent

import datadog.trace.core.serialization.ByteBufferConsumer
import datadog.trace.core.serialization.FlushingBuffer
import datadog.trace.core.serialization.msgpack.MsgPackWriter
import datadog.trace.test.util.DDSpecification
import org.junit.Assert
import org.msgpack.core.MessageFormat
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker

import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED
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

class TraceMapperV04PayloadTest extends DDSpecification {

  def "test traces written correctly"() {
    setup:
    List<List<TraceGenerator.PojoSpan>> traces = generateRandomTraces(traceCount, lowCardinality)
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4()
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper)
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(bufferSize, verifier))
    when:
    boolean tracesFitInBuffer = true
    for (List<TraceGenerator.PojoSpan> trace : traces) {
      if (!packer.format(trace, traceMapper)) {
        tracesFitInBuffer = false
      }
    }
    packer.flush()

    then:
    if (tracesFitInBuffer) {
      verifier.verifyTracesConsumed()
    }

    where:
    bufferSize | traceCount | lowCardinality
    20 << 10   | 0          | true
    20 << 10   | 1          | true
    30 << 10   | 1          | true
    30 << 10   | 2          | true
    20 << 10   | 0          | false
    20 << 10   | 1          | false
    30 << 10   | 1          | false
    30 << 10   | 2          | false
    100 << 10  | 0          | true
    100 << 10  | 1          | true
    100 << 10  | 10         | true
    100 << 10  | 100        | true
    100 << 10  | 1000       | true
    100 << 10  | 0          | false
    100 << 10  | 1          | false
    100 << 10  | 10         | false
    100 << 10  | 100        | false
    100 << 10  | 1000       | false
  }

  private static final class PayloadVerifier implements ByteBufferConsumer, WritableByteChannel {

    private final List<List<TraceGenerator.PojoSpan>> expectedTraces
    private final TraceMapperV0_4 mapper
    private ByteBuffer captured = ByteBuffer.allocate(200 << 10)

    private int position = 0

    private PayloadVerifier(List<List<TraceGenerator.PojoSpan>> traces, TraceMapperV0_4 mapper) {
      this.expectedTraces = traces
      this.mapper = mapper
    }

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      if (expectedTraces.isEmpty() && messageCount == 0) {
        return
      }
      try {
        Payload payload = mapper.newPayload().withBody(messageCount, buffer)
        payload.writeTo(this)
        captured.flip()
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(captured)
        int traceCount = unpacker.unpackArrayHeader()
        for (int i = 0; i < traceCount; ++i) {
          List<TraceGenerator.PojoSpan> expectedTrace = expectedTraces.get(position++)
          int spanCount = unpacker.unpackArrayHeader()
          assertEquals(expectedTrace.size(), spanCount)
          for (int k = 0; k < spanCount; ++k) {
            TraceGenerator.PojoSpan expectedSpan = expectedTrace.get(k)
            int elementCount = unpacker.unpackMapHeader()
            assertEquals(12, elementCount)
            assertEquals("service", unpacker.unpackString())
            String serviceName = unpacker.unpackString()
            assertEqualsWithNullAsEmpty(expectedSpan.getServiceName(), serviceName)
            assertEquals("name", unpacker.unpackString())
            String operationName = unpacker.unpackString()
            assertEqualsWithNullAsEmpty(expectedSpan.getOperationName(), operationName)
            assertEquals("resource", unpacker.unpackString())
            String resourceName = unpacker.unpackString()
            assertEqualsWithNullAsEmpty(expectedSpan.getResourceName(), resourceName)
            assertEquals("trace_id", unpacker.unpackString())
            long traceId = unpacker.unpackLong()
            assertEquals(expectedSpan.getTraceId().toLong(), traceId)
            assertEquals("span_id", unpacker.unpackString())
            long spanId = unpacker.unpackLong()
            assertEquals(expectedSpan.getSpanId().toLong(), spanId)
            assertEquals("parent_id", unpacker.unpackString())
            long parentId = unpacker.unpackLong()
            assertEquals(expectedSpan.getParentId().toLong(), parentId)
            assertEquals("start", unpacker.unpackString())
            long startTime = unpacker.unpackLong()
            assertEquals(expectedSpan.getStartTime(), startTime)
            assertEquals("duration", unpacker.unpackString())
            long duration = unpacker.unpackLong()
            assertEquals(expectedSpan.getDurationNano(), duration)
            assertEquals("type", unpacker.unpackString())
            String type = unpacker.unpackString()
            assertEquals(expectedSpan.getType(), type)
            assertEquals("error", unpacker.unpackString())
            int error = unpacker.unpackInt()
            assertEquals(expectedSpan.getError(), error)
            assertEquals("metrics", unpacker.unpackString())
            int metricsSize = unpacker.unpackMapHeader()
            HashMap<String, Number> metrics = new HashMap<>()
            for (int j = 0; j < metricsSize; ++j) {
              String key = unpacker.unpackString()
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
              if (DD_MEASURED.toString() == key) {
                assert ((n == 1) && expectedSpan.isMeasured()) || !expectedSpan.isMeasured()
              } else {
                metrics.put(key, n)
              }
            }
            for (Map.Entry<String, Number> metric : metrics.entrySet()) {
              if (metric.getValue() instanceof Double) {
                assertEquals(expectedSpan.getUnsafeMetrics().get(metric.getKey()).doubleValue(), metric.getValue().doubleValue(), 0.001)
              } else {
                assertEquals(expectedSpan.getUnsafeMetrics().get(metric.getKey()), metric.getValue())
              }
            }
            assertEquals("meta", unpacker.unpackString())
            int metaSize = unpacker.unpackMapHeader()
            HashMap<String, String> meta = new HashMap<>()
            for (int j = 0; j < metaSize; ++j) {
              meta.put(unpacker.unpackString(), unpacker.unpackString())
            }
            for (Map.Entry<String, String> entry : meta.entrySet()) {
              Object tag = expectedSpan.getTag(entry.getKey())
              if (null != tag) {
                assertEquals(String.valueOf(tag), entry.getValue())
              } else {
                assertEquals(expectedSpan.getBaggage().get(entry.getKey()), entry.getValue())
              }
            }
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
      if (captured.remaining() < src.remaining()) {
        ByteBuffer newBuffer = ByteBuffer.allocate(captured.capacity() + src.capacity())
        captured.flip()
        newBuffer.put(captured)
        captured = newBuffer
        return write(src)
      }
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
    void close() {
    }
  }

  private static void assertEqualsWithNullAsEmpty(CharSequence expected, CharSequence actual) {
    if (null == expected) {
      assertEquals("", actual)
    } else {
      assertEquals(expected.toString(), actual.toString())
    }
  }
}
