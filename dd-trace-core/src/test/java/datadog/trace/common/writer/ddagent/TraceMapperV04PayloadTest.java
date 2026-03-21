package datadog.trace.common.writer.ddagent;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED;
import static datadog.trace.common.writer.TraceGenerator.generateRandomTraces;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.msgpack.core.MessageFormat.FLOAT32;
import static org.msgpack.core.MessageFormat.FLOAT64;
import static org.msgpack.core.MessageFormat.INT16;
import static org.msgpack.core.MessageFormat.INT32;
import static org.msgpack.core.MessageFormat.INT64;
import static org.msgpack.core.MessageFormat.INT8;
import static org.msgpack.core.MessageFormat.NEGFIXINT;
import static org.msgpack.core.MessageFormat.POSFIXINT;
import static org.msgpack.core.MessageFormat.UINT16;
import static org.msgpack.core.MessageFormat.UINT32;
import static org.msgpack.core.MessageFormat.UINT64;
import static org.msgpack.core.MessageFormat.UINT8;

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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
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

class TraceMapperV04PayloadTest {

  static Stream<Arguments> testTracesWrittenCorrectlyArguments() {
    return Stream.of(
        Arguments.of(20 << 10, 0, true),
        Arguments.of(20 << 10, 1, true),
        Arguments.of(30 << 10, 1, true),
        Arguments.of(30 << 10, 2, true),
        Arguments.of(20 << 10, 0, false),
        Arguments.of(20 << 10, 1, false),
        Arguments.of(30 << 10, 1, false),
        Arguments.of(30 << 10, 2, false),
        Arguments.of(100 << 10, 0, true),
        Arguments.of(100 << 10, 1, true),
        Arguments.of(100 << 10, 10, true),
        Arguments.of(100 << 10, 100, true),
        Arguments.of(100 << 10, 1000, true),
        Arguments.of(100 << 10, 0, false),
        Arguments.of(100 << 10, 1, false),
        Arguments.of(100 << 10, 10, false),
        Arguments.of(100 << 10, 100, false),
        Arguments.of(100 << 10, 1000, false));
  }

  @ParameterizedTest
  @MethodSource("testTracesWrittenCorrectlyArguments")
  void testTracesWrittenCorrectly(int bufferSize, int traceCount, boolean lowCardinality)
      throws Exception {
    @SuppressWarnings("unchecked")
    List<List<TraceGenerator.PojoSpan>> traces =
        (List<List<TraceGenerator.PojoSpan>>)
            (List<?>) generateRandomTraces(traceCount, lowCardinality);
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

  @TableTest({
    "scenario               | traceId              | spanId | parentId",
    "ONE spanId2 parentId3  | DD64bTraceId.ONE     | 2      | 3       ",
    "MAX spanId2 parentId3  | DD64bTraceId.MAX     | 2      | 3       ",
    "from-10 spanId-11 p-12 | DD64bTraceId.from-10 | -11    | -12     "
  })
  @ParameterizedTest(name = "[{index}] test full 64-bit trace and span identifiers - {0}")
  void testFull64BitTraceAndSpanIdentifiers(String traceIdStr, long spanId, long parentId)
      throws Exception {
    DDTraceId traceId = parseTraceId(traceIdStr);
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
            new HashMap<>(),
            new HashMap<>(),
            "type",
            false,
            0,
            0,
            "origin");
    List<TraceGenerator.PojoSpan> trace = Arrays.asList(span);
    List<List<TraceGenerator.PojoSpan>> traces = Arrays.asList(trace);
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier));

    packer.format(trace, traceMapper);
    packer.flush();

    verifier.verifyTracesConsumed();
  }

  @Test
  void testMetaStructSupport() throws Exception {
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
            new HashMap<>(),
            new HashMap<>(),
            "type",
            false,
            0,
            0,
            "origin");
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    List<Map<String, String>> stackList = new java.util.ArrayList<>();
    for (StackTraceElement element : stackTrace) {
      Map<String, String> entry = new HashMap<>();
      entry.put("file", element.getFileName() != null ? element.getFileName() : "");
      entry.put("class_name", element.getClassName() != null ? element.getClassName() : "");
      entry.put("function", element.getMethodName() != null ? element.getMethodName() : "");
      stackList.add(entry);
    }
    span.setMetaStruct("stack", stackList);
    List<TraceGenerator.PojoSpan> trace = Arrays.asList(span);
    List<List<TraceGenerator.PojoSpan>> traces = Arrays.asList(trace);
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4();
    PayloadVerifier verifier =
        new PayloadVerifier(
            traces,
            traceMapper,
            (List<?> expected, byte[] received) -> {
              try {
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
              } catch (IOException e) {
                Assertions.fail(e.getMessage());
              }
            });
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier));

    packer.format(trace, traceMapper);
    packer.flush();

    verifier.verifyTracesConsumed();
  }

  @Test
  void testProcessTagsSerialization() throws Exception {
    assertNotNull(ProcessTags.getTagsForSerialization());
    List<TraceGenerator.PojoSpan> spans = new java.util.ArrayList<>();
    for (int i = 1; i <= 2; i++) {
      spans.add(
          new TraceGenerator.PojoSpan(
              "service",
              "operation",
              "resource",
              DDTraceId.ONE,
              (long) i,
              -1L,
              123L,
              456L,
              0,
              new HashMap<>(),
              new HashMap<>(),
              "type",
              false,
              0,
              0,
              "origin"));
    }

    List<List<TraceGenerator.PojoSpan>> traces = Arrays.asList(spans);
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier));

    packer.format(spans, traceMapper);
    packer.flush();

    verifier.verifyTracesConsumed();
  }

  private static DDTraceId parseTraceId(String str) {
    switch (str.trim()) {
      case "DD64bTraceId.ONE":
        return DD64bTraceId.ONE;
      case "DD64bTraceId.MAX":
        return DD64bTraceId.MAX;
      case "DD64bTraceId.from-10":
        return DD64bTraceId.from(-10);
      default:
        throw new IllegalArgumentException("Unknown traceId: " + str);
    }
  }

  private static final class PayloadVerifier implements ByteBufferConsumer, WritableByteChannel {

    private final List<List<TraceGenerator.PojoSpan>> expectedTraces;
    private final TraceMapperV0_4 mapper;
    private ByteBuffer captured = ByteBuffer.allocate(200 << 10);
    private MetaStructVerifier<?> metaStructVerifier;

    private int position = 0;

    private PayloadVerifier(List<List<TraceGenerator.PojoSpan>> traces, TraceMapperV0_4 mapper) {
      this(traces, mapper, null);
    }

    private PayloadVerifier(
        List<List<TraceGenerator.PojoSpan>> traces,
        TraceMapperV0_4 mapper,
        MetaStructVerifier<?> metaStructVerifier) {
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
              Number n = null;
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
                  n = unpacker.unpackInt();
                  break;
                case INT64:
                case UINT64:
                  n = unpacker.unpackLong();
                  break;
                case FLOAT32:
                  n = unpacker.unpackFloat();
                  break;
                case FLOAT64:
                  n = unpacker.unpackDouble();
                  break;
                default:
                  Assertions.fail("Unexpected type in metrics values: " + format);
              }
              if (DD_MEASURED.toString().equals(key)) {
                assertTrue(
                    ((n.intValue() == 1) && expectedSpan.isMeasured())
                        || !expectedSpan.isMeasured());
              } else if (DDSpanContext.PRIORITY_SAMPLING_KEY.equals(key)) {
                // check that priority sampling is only on first and last span
                if (k == 0 || k == spanCount - 1) {
                  assertEquals(expectedSpan.samplingPriority(), n.intValue());
                } else {
                  assertFalse(expectedSpan.hasSamplingPriority());
                }
              } else {
                metrics.put(key, n);
              }
            }
            for (Map.Entry<String, Number> metric : metrics.entrySet()) {
              if (metric.getValue() instanceof Double || metric.getValue() instanceof Float) {
                assertEquals(
                    ((Number) expectedSpan.getTag(metric.getKey())).doubleValue(),
                    metric.getValue().doubleValue(),
                    0.001);
              } else {
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
                  verifyMetaStruct(metaStructVerifier, metaStruct.get(field), binary);
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
    if (null == expected) {
      assertEquals("", actual);
    } else {
      assertEquals(expected.toString(), actual.toString());
    }
  }

  @SuppressWarnings("unchecked")
  private static <E> void verifyMetaStruct(
      MetaStructVerifier<E> verifier, Object expected, byte[] received) {
    verifier.verify((E) expected, received);
  }

  private interface MetaStructVerifier<E> {
    void verify(E expected, byte[] received);
  }
}
