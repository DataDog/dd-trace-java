package datadog.trace.common.writer.ddagent

import datadog.trace.api.DDId
import datadog.trace.core.CoreSpan
import datadog.trace.core.serialization.ByteBufferConsumer
import datadog.trace.core.serialization.msgpack.MsgPackWriter
import datadog.trace.test.util.DDSpecification
import org.junit.Assert
import org.msgpack.core.MessageFormat
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker

import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.util.concurrent.atomic.AtomicInteger

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

class TraceMapperV05PayloadTest extends DDSpecification {


  def "dictionary overflow causes a flush"() {
    setup:
    // 4x 36 ASCII characters and 2 bytes of msgpack string prefix
    int dictionarySpacePerTrace = 4 * (36 + 2)
    int dictionarySize = 10 * 1024
    int traceCountToOverflowDictionary = dictionarySize / dictionarySpacePerTrace + 1
    List<List<CoreSpan>> traces = new ArrayList<>(traceCountToOverflowDictionary)
    for (int i = 0; i < traceCountToOverflowDictionary; ++i) {
      // these traces must have deterministic size, but each string value
      // must be unique to ensure no dictionary code reuse
      traces.add(Collections.singletonList(new TraceGenerator.PojoSpan(
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        DDId.ZERO,
        DDId.ZERO,
        DDId.ZERO,
        10000,
        100,
        0,
        Collections.emptyMap(),
        Collections.emptyMap(),
        Collections.emptyMap(),
        UUID.randomUUID().toString(),
        false
      )))
    }
    TraceMapperV0_5 traceMapper = new TraceMapperV0_5(dictionarySize)
    List<List<CoreSpan>> flushedTraces = new ArrayList<>(traces)
    // the last one won't be flushed
    flushedTraces.remove(traces.size() - 1)
    PayloadVerifier verifier = new PayloadVerifier(flushedTraces, traceMapper)
    // 100KB body
    MsgPackWriter packer = new MsgPackWriter(verifier, ByteBuffer.allocate(100 * 1024))
    when:
    for (List<CoreSpan> trace : traces) {
      packer.format(trace, traceMapper)
    }
    then:
    verifier.verifyTracesConsumed()
  }

  def "body overflow causes a flush"() {
    setup:
    // 4x 36 ASCII characters and 2 bytes of msgpack string prefix
    int dictionarySpacePerTrace = 4 * (36 + 2)
    // enough space for two traces with distinct string values, plus the header
    int dictionarySize = dictionarySpacePerTrace * 2 + 5
    TraceMapperV0_5 traceMapper = new TraceMapperV0_5(dictionarySize)
    List<CoreSpan> repeatedTrace = Collections.singletonList(new TraceGenerator.PojoSpan(
      UUID.randomUUID().toString(),
      UUID.randomUUID().toString(),
      UUID.randomUUID().toString(),
      DDId.ZERO,
      DDId.ZERO,
      DDId.ZERO,
      10000,
      100,
      0,
      Collections.emptyMap(),
      Collections.emptyMap(),
      Collections.emptyMap(),
      UUID.randomUUID().toString(),
      false))
    int traceSize = calculateSize(repeatedTrace)
    int tracesRequiredToOverflowBody = traceMapper.messageBufferSize() / traceSize
    List<List<CoreSpan>> traces = new ArrayList<>(tracesRequiredToOverflowBody)
    for (int i = 0; i < tracesRequiredToOverflowBody; ++i) {
      traces.add(repeatedTrace)
    }
    // the last one won't be flushed
    List<List<CoreSpan>> flushedTraces = new ArrayList<>(traces)
    flushedTraces.remove(traces.size() - 1)
    // need space for the overflowing buffer, the dictionary, and two small array headers
    PayloadVerifier verifier = new PayloadVerifier(flushedTraces, traceMapper, (2 << 20) + dictionarySize + 1 + 1 + 5)
    // 2MB body
    MsgPackWriter packer = new MsgPackWriter(verifier, ByteBuffer.allocate(2 << 20))
    when:
    for (List<CoreSpan> trace : traces) {
      packer.format(trace, traceMapper)
    }
    then:
    verifier.verifyTracesConsumed()
  }

  def "test dictionary compressed traces written correctly"() {
    setup:
    List<List<CoreSpan>> traces = generateRandomTraces(traceCount, lowCardinality)
    TraceMapperV0_5 traceMapper = new TraceMapperV0_5(dictionarySize)
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper)
    MsgPackWriter packer = new MsgPackWriter(verifier, ByteBuffer.allocate(bufferSize))
    when:
    boolean tracesFitInBuffer = true
    try {
      for (List<CoreSpan> trace : traces) {
        packer.format(trace, traceMapper)
      }
    } catch (BufferOverflowException e) {
      tracesFitInBuffer = false
    }
    packer.flush()

    then:
    if (tracesFitInBuffer) {
      verifier.verifyTracesConsumed()
    }

    where:
    bufferSize | dictionarySize | traceCount | lowCardinality
    10 << 10   | 10 << 10       | 0          | true
    10 << 10   | 10 << 10       | 1          | true
    10 << 10   | 10 << 10       | 10         | true
    10 << 10   | 10 << 10       | 100        | true
    10 << 10   | 100 << 10      | 1          | true
    10 << 10   | 100 << 10      | 10         | true
    10 << 10   | 100 << 10      | 100        | true
    10 << 10   | 10 << 10       | 0          | false
    10 << 10   | 10 << 10       | 1          | false
    10 << 10   | 10 << 10       | 10         | false
    10 << 10   | 10 << 10       | 100        | false
    10 << 10   | 100 << 10      | 1          | false
    10 << 10   | 100 << 10      | 10         | false
    10 << 10   | 100 << 10      | 100        | false
    100 << 10  | 10 << 10       | 0          | true
    100 << 10  | 10 << 10       | 1          | true
    100 << 10  | 10 << 10       | 10         | true
    100 << 10  | 10 << 10       | 100        | true
    100 << 10  | 100 << 10      | 1          | true
    100 << 10  | 100 << 10      | 10         | true
    100 << 10  | 100 << 10      | 100        | true
    100 << 10  | 10 << 10       | 0          | false
    100 << 10  | 10 << 10       | 1          | false
    100 << 10  | 10 << 10       | 10         | false
    100 << 10  | 10 << 10       | 100        | false
    100 << 10  | 100 << 10      | 1          | false
    100 << 10  | 100 << 10      | 10         | false
    100 << 10  | 100 << 10      | 100        | false
    100 << 10  | 100 << 10      | 1000       | false
  }

  private static final class PayloadVerifier implements ByteBufferConsumer, WritableByteChannel {

    private final List<List<CoreSpan>> expectedTraces
    private final TraceMapperV0_5 mapper
    private final ByteBuffer captured

    private int position = 0

    private PayloadVerifier(List<List<CoreSpan>> traces, TraceMapperV0_5 mapper) {
      this (traces, mapper, 200 << 10)
    }

    private PayloadVerifier(List<List<CoreSpan>> traces, TraceMapperV0_5 mapper, int size) {
      this.expectedTraces = traces
      this.mapper = mapper
      this.captured = ByteBuffer.allocate(size)
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
          List<CoreSpan> expectedTrace = expectedTraces.get(position++)
          int spanCount = unpacker.unpackArrayHeader()
          assertEquals(expectedTrace.size(), spanCount)
          for (int k = 0; k < spanCount; ++k) {
            CoreSpan expectedSpan = expectedTrace.get(k)
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
              if (DD_MEASURED.toString() == key) {
                assert ((n == 1) && expectedSpan.isMeasured()) || !expectedSpan.isMeasured()
              } else {
                metrics.put(key, n)
              }
            }
            for (Map.Entry<String, Number> metric : metrics.entrySet()) {
              if (metric.getValue() instanceof Double) {
                assertEquals(expectedSpan.getMetrics().get(metric.getKey()).doubleValue(), metric.getValue().doubleValue(), 0.001)
              } else {
                assertEquals(expectedSpan.getMetrics().get(metric.getKey()), metric.getValue())
              }
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

  static int calculateSize(List<CoreSpan> trace) {
    ByteBuffer buffer = ByteBuffer.allocate(1024)
    AtomicInteger size = new AtomicInteger()
    def packer = new MsgPackWriter(new ByteBufferConsumer() {
      @Override
      void accept(int messageCount, ByteBuffer buffy) {
        size.set(buffy.limit() - buffy.position() - 1)
      }
    }, buffer)
    packer.format(trace, new TraceMapperV0_5(1024))
    packer.flush()
    return size.get()
  }
}
