package datadog.trace.common.writer.ddagent

import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.api.Config
import datadog.trace.api.DD64bTraceId
import datadog.trace.api.DDTags
import datadog.trace.api.DDTraceId
import datadog.trace.api.ProcessTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.writer.Payload
import datadog.trace.common.writer.TraceGenerator
import datadog.trace.core.DDSpanContext
import datadog.trace.test.util.DDSpecification
import org.junit.jupiter.api.Assertions
import org.msgpack.core.MessageFormat
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker

import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED
import static datadog.trace.common.writer.TraceGenerator.generateRandomTraces
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue
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
        verifier.skipLargeTrace()
        tracesFitInBuffer = false
        // in the real like the mapper is always reset each trace.
        // here we need to force it when we fail since the buffer will be reset as well
        traceMapper.reset()
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

  def "test full 64-bit trace and span identifiers"() {
    setup:
    def span = new TraceGenerator.PojoSpan(
    "service",
    "operation",
    "resource",
    traceId,
    spanId,
    parentId,
    123L,
    456L,
    0,
    [:],
    [:],
    "type",
    false,
    0,
    0,
    "origin")
    def traces = [[span]]
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4()
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper)
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier))

    when:
    packer.format([span], traceMapper)
    packer.flush()

    then:
    verifier.verifyTracesConsumed()

    where:
    traceId                | spanId | parentId
    DD64bTraceId.ONE       | 2L     | 3L
    DD64bTraceId.MAX       | 2L     | 3L
    DD64bTraceId.from(-10) | -11L   | -12L
  }

  void 'test metaStruct support'() {
    given:
    def span = new TraceGenerator.PojoSpan(
    'service',
    'operation',
    'resource',
    DDTraceId.ONE,
    1L,
    -1L,
    123L,
    456L,
    0,
    [:],
    [:],
    'type',
    false,
    0,
    0,
    'origin')
    span.setMetaStruct('stack', Thread.currentThread().stackTrace.toList().collect {
      [
        file: it.fileName ?: '',
        class_name: it.className ?: '',
        function: it.methodName ?: ''
      ]
    })
    def traces = [[span]]
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4()
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper, (List<?> expected, byte[] received) -> {
      def unpacker = MessagePack.newDefaultUnpacker(received)
      def size = unpacker.unpackArrayHeader()
      assertEquals(expected.size(), size)
      expected.eachWithIndex {
        def stackEntry, int i ->
        int fields = unpacker.unpackMapHeader()
        (0..<fields).each {
          String field = unpacker.unpackString()
          assertEquals(stackEntry[field], unpacker.unpackString())
        }
      }
    })
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier))

    when:
    packer.format([span], traceMapper)
    packer.flush()

    then:
    verifier.verifyTracesConsumed()
  }

  void 'test process tags serialization'() {
    setup:
    assertNotNull(ProcessTags.tagsForSerialization)
    def spans = (1..2).collect {
      new TraceGenerator.PojoSpan(
      'service',
      'operation',
      'resource',
      DDTraceId.ONE,
      it,
      -1L,
      123L,
      456L,
      0,
      [:],
      [:],
      'type',
      false,
      0,
      0,
      'origin')
    }

    def traces = [spans]
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4()
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper)
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier))

    when:
    packer.format(spans, traceMapper)
    packer.flush()

    then:
    verifier.verifyTracesConsumed()
  }

  private static final class PayloadVerifier implements ByteBufferConsumer, WritableByteChannel {

    private final List<List<TraceGenerator.PojoSpan>> expectedTraces
    private final TraceMapperV0_4 mapper
    private ByteBuffer captured = ByteBuffer.allocate(200 << 10)
    private MetaStructVerifier<?> metaStructVerifier

    private int position = 0

    private PayloadVerifier(List<List<TraceGenerator.PojoSpan>> traces, TraceMapperV0_4 mapper, MetaStructVerifier<?> metaStructVerifier = null) {
      this.expectedTraces = traces
      this.mapper = mapper
      this.metaStructVerifier = metaStructVerifier
    }

    void skipLargeTrace() {
      ++position
    }

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      if (expectedTraces.isEmpty() && messageCount == 0) {
        return
      }
      int processTagsCount = 0
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
            boolean hasMetaStruct = !expectedSpan.getMetaStruct().isEmpty()
            assertEquals(hasMetaStruct ? 13 : 12, elementCount)
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
            long traceId = unpacker.unpackValue().asNumberValue().toLong()
            assertEquals(expectedSpan.getTraceId().toLong(), traceId)
            assertEquals("span_id", unpacker.unpackString())
            long spanId = unpacker.unpackValue().asNumberValue().toLong()
            assertEquals(expectedSpan.getSpanId(), spanId)
            assertEquals("parent_id", unpacker.unpackString())
            long parentId = unpacker.unpackValue().asNumberValue().toLong()
            assertEquals(expectedSpan.getParentId(), parentId)
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
                Assertions.fail("Unexpected type in metrics values: " + format)
              }
              if (DD_MEASURED.toString() == key) {
                assert ((n == 1) && expectedSpan.isMeasured()) || !expectedSpan.isMeasured()
              } else if (DDSpanContext.PRIORITY_SAMPLING_KEY == key) {
                //check that priority sampling is only on first and last span
                if (k == 0 || k == spanCount - 1) {
                  assertEquals(expectedSpan.samplingPriority(), n.intValue())
                } else {
                  assertFalse(expectedSpan.hasSamplingPriority())
                }
              } else {
                metrics.put(key, n)
              }
            }
            for (Map.Entry<String, Number> metric : metrics.entrySet()) {
              if (metric.getValue() instanceof Double || metric.getValue() instanceof Float) {
                assertEquals(((Number) expectedSpan.getTag(metric.getKey())).doubleValue(), metric.getValue().doubleValue(), 0.001)
              } else {
                assertEquals(expectedSpan.getTag(metric.getKey()), metric.getValue())
              }
            }
            assertEquals("meta", unpacker.unpackString())
            int metaSize = unpacker.unpackMapHeader()
            HashMap<String, String> meta = new HashMap<>()
            for (int j = 0; j < metaSize; ++j) {
              meta.put(unpacker.unpackString(), unpacker.unpackString())
            }
            for (Map.Entry<String, String> entry : meta.entrySet()) {
              if (Tags.HTTP_STATUS.equals(entry.getKey())) {
                assertEquals(String.valueOf(expectedSpan.getHttpStatusCode()), entry.getValue())
              } else if (DDTags.ORIGIN_KEY.equals(entry.getKey())) {
                assertEquals(expectedSpan.getOrigin(), entry.getValue())
              } else if (DDTags.PROCESS_TAGS.equals(entry.getKey())) {
                assertTrue(Config.get().isExperimentalPropagateProcessTagsEnabled())
                assertEquals(0, k)
                assertEquals(ProcessTags.tagsForSerialization.toString(), entry.getValue())
                processTagsCount++
              } else {
                Object tag = expectedSpan.getTag(entry.getKey())
                if (null != tag) {
                  assertEquals(String.valueOf(tag), entry.getValue())
                } else {
                  assertEquals(expectedSpan.getBaggage().get(entry.getKey()), entry.getValue())
                }
              }
            }
            if (hasMetaStruct) {
              Map<String, Object> metaStruct = expectedSpan.getMetaStruct()
              assertEquals("meta_struct", unpacker.unpackString())
              int metaStructSize = unpacker.unpackMapHeader()
              for (int j = 0; j < metaStructSize; ++j) {
                String field = unpacker.unpackString()
                if (metaStructVerifier != null) {
                  byte[] binary = new byte[unpacker.unpackBinaryHeader()]
                  unpacker.readPayload(binary)
                  metaStructVerifier.verify(metaStruct.get(field), binary)
                }
              }
            }
          }
        }
      } catch (IOException e) {
        Assertions.fail(e.getMessage())
      } finally {
        mapper.reset()
        captured.position(0)
        captured.limit(captured.capacity())
        assert processTagsCount == (Config.get().isExperimentalPropagateProcessTagsEnabled() ? 1 : 0)
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

  private static interface MetaStructVerifier<E> {
    void verify(final E expected, final byte[] received)
  }
}
