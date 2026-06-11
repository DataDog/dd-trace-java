package datadog.trace.common.writer.ddagent;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED;
import static datadog.trace.common.writer.TraceGenerator.generateRandomTraces;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.Config;
import datadog.trace.api.DD64bTraceId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.ProcessTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.TraceGenerator;
import datadog.trace.core.DDSpanContext;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.tabletest.junit.TableTest;

class TraceMapperV04PayloadTest extends DDJavaSpecification {

  @TableTest({
    "scenario              | bufferSize | traceCount | lowCardinality",
    "0 traces low card     | 20480      | 0          | true          ",
    "1 trace low card 20k  | 20480      | 1          | true          ",
    "1 trace low card 30k  | 30720      | 1          | true          ",
    "2 traces low card     | 30720      | 2          | true          ",
    "0 traces high card    | 20480      | 0          | false         ",
    "1 trace high card 20k | 20480      | 1          | false         ",
    "1 trace high card 30k | 30720      | 1          | false         ",
    "2 traces high card    | 30720      | 2          | false         ",
    "0 traces 100k low     | 102400     | 0          | true          ",
    "1 trace 100k low      | 102400     | 1          | true          ",
    "10 traces 100k low    | 102400     | 10         | true          ",
    "100 traces 100k low   | 102400     | 100        | true          ",
    "1000 traces 100k low  | 102400     | 1000       | true          ",
    "0 traces 100k high    | 102400     | 0          | false         ",
    "1 trace 100k high     | 102400     | 1          | false         ",
    "10 traces 100k high   | 102400     | 10         | false         ",
    "100 traces 100k high  | 102400     | 100        | false         ",
    "1000 traces 100k high | 102400     | 1000       | false         "
  })
  void testTracesWrittenCorrectly(int bufferSize, int traceCount, boolean lowCardinality) {
    List<List<TraceGenerator.PojoSpan>> traces = generateRandomTraces(traceCount, lowCardinality);
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(bufferSize, verifier));

    boolean tracesFitInBuffer = true;
    for (List<TraceGenerator.PojoSpan> trace : traces) {
      if (!packer.format(trace, traceMapper)) {
        verifier.skipLargeTrace();
        tracesFitInBuffer = false;
        // in the real life the mapper is always reset each trace.
        // here we need to force it when we fail since the buffer will be reset as well
        traceMapper.reset();
      }
    }
    packer.flush();

    if (tracesFitInBuffer) {
      verifier.verifyTracesConsumed();
    }
  }

  static Stream<Arguments> testFull64BitTraceAndSpanIdentifiersArguments() {
    return Stream.of(
        arguments(DD64bTraceId.ONE, 2L, 3L),
        arguments(DD64bTraceId.MAX, 2L, 3L),
        arguments(DD64bTraceId.from(-10), -11L, -12L));
  }

  @ParameterizedTest
  @MethodSource("testFull64BitTraceAndSpanIdentifiersArguments")
  void testFull64BitTraceAndSpanIdentifiers(DDTraceId traceId, long spanId, long parentId) {
    TraceGenerator.PojoSpan span =
        new TraceGenerator.PojoSpan(
            "service",
            "operation",
            "resource",
            traceId,
            spanId,
            parentId,
            123L,
            456L,
            0,
            Collections.emptyMap(),
            Collections.emptyMap(),
            "type",
            false,
            0,
            0,
            "origin");
    List<List<TraceGenerator.PojoSpan>> traces =
        Collections.singletonList(Collections.singletonList(span));
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier));

    packer.format(Collections.singletonList(span), traceMapper);
    packer.flush();

    verifier.verifyTracesConsumed();
  }

  @Test
  void testMetaStructSupport() {
    TraceGenerator.PojoSpan span =
        new TraceGenerator.PojoSpan(
            "service",
            "operation",
            "resource",
            DDTraceId.ONE,
            1L,
            -1L,
            123L,
            456L,
            0,
            Collections.emptyMap(),
            Collections.emptyMap(),
            "type",
            false,
            0,
            0,
            "origin");

    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    List<Map<String, String>> stackList = new ArrayList<>();
    for (StackTraceElement element : stackTrace) {
      Map<String, String> entry = new HashMap<>();
      entry.put("file", element.getFileName() != null ? element.getFileName() : "");
      entry.put("class_name", element.getClassName());
      entry.put("function", element.getMethodName());
      stackList.add(entry);
    }
    span.setMetaStruct("stack", stackList);

    List<List<TraceGenerator.PojoSpan>> traces =
        Collections.singletonList(Collections.singletonList(span));
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4();
    PayloadVerifier verifier =
        new PayloadVerifier(
            traces,
            traceMapper,
            (expectedObj, received) -> {
              List<?> expected = (List<?>) expectedObj;
              MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(received);
              int size = unpacker.unpackArrayHeader();
              assertEquals(expected.size(), size);
              for (int i = 0; i < size; i++) {
                Map<?, ?> stackEntry = (Map<?, ?>) expected.get(i);
                int fields = unpacker.unpackMapHeader();
                for (int j = 0; j < fields; j++) {
                  String field = unpacker.unpackString();
                  assertEquals(stackEntry.get(field), unpacker.unpackString());
                }
              }
            });
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier));

    packer.format(Collections.singletonList(span), traceMapper);
    packer.flush();

    verifier.verifyTracesConsumed();
  }

  @Test
  void testProcessTagsSerialization() {
    assertNotNull(ProcessTags.getTagsForSerialization());

    List<TraceGenerator.PojoSpan> spans = new ArrayList<>();
    for (int spanId = 1; spanId <= 2; spanId++) {
      spans.add(
          new TraceGenerator.PojoSpan(
              "service",
              "operation",
              "resource",
              DDTraceId.ONE,
              spanId,
              -1L,
              123L,
              456L,
              0,
              Collections.emptyMap(),
              Collections.emptyMap(),
              "type",
              false,
              0,
              0,
              "origin"));
    }

    List<List<TraceGenerator.PojoSpan>> traces = Collections.singletonList(spans);
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier));

    packer.format(spans, traceMapper);
    packer.flush();

    verifier.verifyTracesConsumed();
  }

  // --- Inner classes ---

  private interface MetaStructVerifier {
    void verify(Object expected, byte[] received) throws IOException;
  }

  private static final class PayloadVerifier implements ByteBufferConsumer, WritableByteChannel {

    private final List<List<TraceGenerator.PojoSpan>> expectedTraces;
    private final TraceMapperV0_4 mapper;
    private ByteBuffer captured = ByteBuffer.allocate(200 << 10);
    private final MetaStructVerifier metaStructVerifier;
    private int position = 0;

    private PayloadVerifier(List<List<TraceGenerator.PojoSpan>> traces, TraceMapperV0_4 mapper) {
      this(traces, mapper, null);
    }

    private PayloadVerifier(
        List<List<TraceGenerator.PojoSpan>> traces,
        TraceMapperV0_4 mapper,
        MetaStructVerifier metaStructVerifier) {
      this.expectedTraces = traces;
      this.mapper = mapper;
      this.metaStructVerifier = metaStructVerifier;
    }

    void skipLargeTrace() {
      ++position;
    }

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      if (expectedTraces.isEmpty() && messageCount == 0) {
        return;
      }
      int processTagsCount = 0;
      try {
        Payload payload = mapper.newPayload().withBody(messageCount, buffer);
        payload.writeTo(this);
        captured.flip();
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(captured);
        int traceCount = unpacker.unpackArrayHeader();
        for (int i = 0; i < traceCount; ++i) {
          List<TraceGenerator.PojoSpan> expectedTrace = expectedTraces.get(position++);
          int spanCount = unpacker.unpackArrayHeader();
          assertEquals(expectedTrace.size(), spanCount);
          for (int k = 0; k < spanCount; ++k) {
            TraceGenerator.PojoSpan expectedSpan = expectedTrace.get(k);
            int elementCount = unpacker.unpackMapHeader();
            boolean hasMetaStruct = !expectedSpan.getMetaStruct().isEmpty();
            assertEquals(hasMetaStruct ? 13 : 12, elementCount);
            assertEquals("service", unpacker.unpackString());
            String serviceName = unpacker.unpackString();
            assertEqualsWithNullAsEmpty(expectedSpan.getServiceName(), serviceName);
            assertEquals("name", unpacker.unpackString());
            String operationName = unpacker.unpackString();
            assertEqualsWithNullAsEmpty(expectedSpan.getOperationName(), operationName);
            assertEquals("resource", unpacker.unpackString());
            String resourceName = unpacker.unpackString();
            assertEqualsWithNullAsEmpty(expectedSpan.getResourceName(), resourceName);
            assertEquals("trace_id", unpacker.unpackString());
            long traceId = unpacker.unpackValue().asNumberValue().toLong();
            assertEquals(expectedSpan.getTraceId().toLong(), traceId);
            assertEquals("span_id", unpacker.unpackString());
            long spanId = unpacker.unpackValue().asNumberValue().toLong();
            assertEquals(expectedSpan.getSpanId(), spanId);
            assertEquals("parent_id", unpacker.unpackString());
            long parentId = unpacker.unpackValue().asNumberValue().toLong();
            assertEquals(expectedSpan.getParentId(), parentId);
            assertEquals("start", unpacker.unpackString());
            long startTime = unpacker.unpackLong();
            assertEquals(expectedSpan.getStartTime(), startTime);
            assertEquals("duration", unpacker.unpackString());
            long duration = unpacker.unpackLong();
            assertEquals(expectedSpan.getDurationNano(), duration);
            assertEquals("type", unpacker.unpackString());
            String type = unpacker.unpackString();
            assertEquals(expectedSpan.getType(), type);
            assertEquals("error", unpacker.unpackString());
            int error = unpacker.unpackInt();
            assertEquals(expectedSpan.getError(), error);
            assertEquals("metrics", unpacker.unpackString());
            int metricsSize = unpacker.unpackMapHeader();
            HashMap<String, Number> metrics = new HashMap<>();
            for (int j = 0; j < metricsSize; ++j) {
              String key = unpacker.unpackString();
              Number metricValue = null;
              MessageFormat format = unpacker.getNextFormat();
              switch (format) {
                case NEGFIXINT:
                case POSFIXINT:
                case INT8:
                case UINT8:
                case INT16:
                case UINT16:
                case INT32:
                case UINT32:
                  metricValue = unpacker.unpackInt();
                  break;
                case INT64:
                case UINT64:
                  metricValue = unpacker.unpackLong();
                  break;
                case FLOAT32:
                  metricValue = unpacker.unpackFloat();
                  break;
                case FLOAT64:
                  metricValue = unpacker.unpackDouble();
                  break;
                default:
                  Assertions.fail("Unexpected type in metrics values: " + format);
              }
              if (DD_MEASURED.toString().equals(key)) {
                assertTrue(metricValue.intValue() == 1 || !expectedSpan.isMeasured());
              } else if (DDSpanContext.PRIORITY_SAMPLING_KEY.equals(key)) {
                // check that priority sampling is only on first and last span
                if (k == 0 || k == spanCount - 1) {
                  assertEquals(expectedSpan.samplingPriority(), metricValue.intValue());
                } else {
                  assertFalse(expectedSpan.hasSamplingPriority());
                }
              } else {
                metrics.put(key, metricValue);
              }
            }
            for (Map.Entry<String, Number> metric : metrics.entrySet()) {
              if (metric.getValue() instanceof Double || metric.getValue() instanceof Float) {
                assertEquals(
                    ((Number) expectedSpan.getTag(metric.getKey())).doubleValue(),
                    metric.getValue().doubleValue(),
                    0.001);
              } else {
                // Groovy compared numerically, Java requires explicit long comparison to avoid
                // Long/Integer type mismatch from different msgpack integer encoding widths
                assertEquals(
                    ((Number) expectedSpan.getTag(metric.getKey())).longValue(),
                    metric.getValue().longValue());
              }
            }
            assertEquals("meta", unpacker.unpackString());
            int metaSize = unpacker.unpackMapHeader();
            HashMap<String, String> meta = new HashMap<>();
            for (int j = 0; j < metaSize; ++j) {
              meta.put(unpacker.unpackString(), unpacker.unpackString());
            }
            for (Map.Entry<String, String> entry : meta.entrySet()) {
              if (Tags.HTTP_STATUS.equals(entry.getKey())) {
                assertEquals(String.valueOf(expectedSpan.getHttpStatusCode()), entry.getValue());
              } else if (DDTags.ORIGIN_KEY.equals(entry.getKey())) {
                assertEquals(expectedSpan.getOrigin(), entry.getValue());
              } else if (DDTags.PROCESS_TAGS.equals(entry.getKey())) {
                assertTrue(Config.get().isExperimentalPropagateProcessTagsEnabled());
                assertEquals(0, k);
                assertEquals(ProcessTags.getTagsForSerialization().toString(), entry.getValue());
                processTagsCount++;
              } else {
                Object tag = expectedSpan.getTag(entry.getKey());
                if (null != tag) {
                  assertEquals(String.valueOf(tag), entry.getValue());
                } else {
                  assertEquals(expectedSpan.getBaggage().get(entry.getKey()), entry.getValue());
                }
              }
            }
            if (hasMetaStruct) {
              Map<String, Object> metaStruct = expectedSpan.getMetaStruct();
              assertEquals("meta_struct", unpacker.unpackString());
              int metaStructSize = unpacker.unpackMapHeader();
              for (int j = 0; j < metaStructSize; ++j) {
                String field = unpacker.unpackString();
                if (metaStructVerifier != null) {
                  byte[] binary = new byte[unpacker.unpackBinaryHeader()];
                  unpacker.readPayload(binary);
                  metaStructVerifier.verify(metaStruct.get(field), binary);
                }
              }
            }
          }
        }
      } catch (IOException e) {
        Assertions.fail(e.getMessage());
      } finally {
        mapper.reset();
        captured.position(0);
        captured.limit(captured.capacity());
        assertEquals(
            Config.get().isExperimentalPropagateProcessTagsEnabled() ? 1 : 0, processTagsCount);
      }
    }

    @Override
    public int write(ByteBuffer src) {
      if (captured.remaining() < src.remaining()) {
        ByteBuffer newBuffer = ByteBuffer.allocate(captured.capacity() + src.capacity());
        captured.flip();
        newBuffer.put(captured);
        captured = newBuffer;
        return write(src);
      }
      captured.put(src);
      return src.position();
    }

    void verifyTracesConsumed() {
      assertEquals(expectedTraces.size(), position);
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void close() {}
  }

  private static void assertEqualsWithNullAsEmpty(CharSequence expected, CharSequence actual) {
    assertEquals(expected == null ? "" : expected.toString(), actual.toString());
  }
}
