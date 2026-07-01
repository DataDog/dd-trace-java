package datadog.trace.civisibility.writer.ddintake;

import static datadog.trace.api.civisibility.CIConstants.MAX_META_STRING_VALUE_LENGTH;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED;
import static datadog.trace.common.writer.TraceGenerator.generateRandomSpan;
import static datadog.trace.common.writer.TraceGenerator.generateRandomTraces;
import static datadog.trace.util.Strings.truncate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.TraceGenerator;
import datadog.trace.core.DDSpanContext;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.tabletest.junit.TableTest;

public class CiTestCycleMapperV1PayloadTest extends DDJavaSpecification {

  @TableTest({
    "scenario                                   | bufferSize | traceCount | lowCardinality",
    "20k buffer, 0 traces, low cardinality      | 20480      | 0          | true          ",
    "20k buffer, 1 trace, low cardinality       | 20480      | 1          | true          ",
    "30k buffer, 1 trace, low cardinality       | 30720      | 1          | true          ",
    "30k buffer, 2 traces, low cardinality      | 30720      | 2          | true          ",
    "20k buffer, 0 traces, high cardinality     | 20480      | 0          | false         ",
    "20k buffer, 1 trace, high cardinality      | 20480      | 1          | false         ",
    "30k buffer, 1 trace, high cardinality      | 30720      | 1          | false         ",
    "30k buffer, 2 traces, high cardinality     | 30720      | 2          | false         ",
    "100k buffer, 0 traces, low cardinality     | 102400     | 0          | true          ",
    "100k buffer, 1 trace, low cardinality      | 102400     | 1          | true          ",
    "100k buffer, 10 traces, low cardinality    | 102400     | 10         | true          ",
    "100k buffer, 100 traces, low cardinality   | 102400     | 100        | true          ",
    "100k buffer, 1000 traces, low cardinality  | 102400     | 1000       | true          ",
    "100k buffer, 0 traces, high cardinality    | 102400     | 0          | false         ",
    "100k buffer, 1 trace, high cardinality     | 102400     | 1          | false         ",
    "100k buffer, 10 traces, high cardinality   | 102400     | 10         | false         ",
    "100k buffer, 100 traces, high cardinality  | 102400     | 100        | false         ",
    "100k buffer, 1000 traces, high cardinality | 102400     | 1000       | false         "
  })
  void testTracesWrittenCorrectly(int bufferSize, int traceCount, boolean lowCardinality) {
    CiVisibilityWellKnownTags wellKnownTags =
        new CiVisibilityWellKnownTags(
            "runtimeid",
            "my-env",
            "language",
            "my-runtime-name",
            "my-runtime-version",
            "my-runtime-vendor",
            "my-os-arch",
            "my-os-platform",
            "my-os-version",
            "false");
    CiTestCycleMapperV1 mapper = new CiTestCycleMapperV1(wellKnownTags, false);

    List<List<TraceGenerator.PojoSpan>> traces = generateRandomTraces(traceCount, lowCardinality);
    PayloadVerifier verifier = new PayloadVerifier(wellKnownTags, traces, mapper);

    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(bufferSize, verifier));

    boolean tracesFitInBuffer = true;
    for (List<TraceGenerator.PojoSpan> trace : traces) {
      if (!packer.format(trace, mapper)) {
        verifier.skipLargeTrace();
        tracesFitInBuffer = false;
      }
    }
    packer.flush();

    if (tracesFitInBuffer) {
      verifier.verifyTracesConsumed();
    }
  }

  @Test
  void verifyTestSuiteIdTestModuleIdAndTestSessionIdAreWrittenAsTopLevelTagsInTestEvent() {
    Map<String, Object> extraTags = new HashMap<>();
    extraTags.put(Tags.TEST_SESSION_ID, DDTraceId.from(123));
    extraTags.put(Tags.TEST_MODULE_ID, 456L);
    extraTags.put(Tags.TEST_SUITE_ID, 789L);
    TraceGenerator.PojoSpan span = generateRandomSpan(InternalSpanTypes.TEST, extraTags);

    Map<String, Object> deserializedSpan = whenASpanIsWritten(span);

    verifyTopLevelTags(deserializedSpan, DDTraceId.from(123), 456L, 789L);

    Map<String, Object> spanContent = getContent(deserializedSpan);
    assertTrue(spanContent.containsKey("trace_id"));
    assertTrue(spanContent.containsKey("span_id"));
    assertTrue(spanContent.containsKey("parent_id"));
  }

  @Test
  void truncatesMetaStringValuesAndPreservesMetricsAndTopLevelIds() {
    String longValue = repeat("a", MAX_META_STRING_VALUE_LENGTH + 1);
    String exactValue = repeat("b", MAX_META_STRING_VALUE_LENGTH);
    Map<String, Object> extraTags = new HashMap<>();
    extraTags.put(Tags.TEST_SESSION_ID, DDTraceId.from(123));
    extraTags.put(Tags.TEST_MODULE_ID, 456L);
    extraTags.put(Tags.TEST_SUITE_ID, 789L);
    extraTags.put("custom.tag", longValue);
    extraTags.put("exact.tag", exactValue);
    extraTags.put("custom.metric", 42);
    TraceGenerator.PojoSpan span = generateRandomSpan(InternalSpanTypes.TEST, extraTags);

    Map<String, Object> deserializedSpan = whenASpanIsWritten(span);

    verifyTopLevelTags(deserializedSpan, DDTraceId.from(123), 456L, 789L);

    Map<String, Object> spanContent = getContent(deserializedSpan);
    Map<String, Object> deserializedMetrics = getMetrics(spanContent);
    Map<String, Object> deserializedMeta = getMeta(spanContent);

    assertEquals(
        longValue.substring(0, MAX_META_STRING_VALUE_LENGTH), deserializedMeta.get("custom.tag"));
    assertEquals(
        MAX_META_STRING_VALUE_LENGTH, ((String) deserializedMeta.get("custom.tag")).length());
    assertEquals(exactValue, deserializedMeta.get("exact.tag"));
    assertEquals(42, deserializedMetrics.get("custom.metric"));
  }

  @Test
  void truncatesPayloadMetadataValues() {
    String longValue = repeat("m", MAX_META_STRING_VALUE_LENGTH + 1);
    CiVisibilityWellKnownTags wellKnownTags =
        new CiVisibilityWellKnownTags(
            longValue, longValue, longValue, longValue, longValue, longValue, longValue, longValue,
            longValue, longValue);
    CiTestCycleMapperV1 mapper = new CiTestCycleMapperV1(wellKnownTags, false);
    List<List<TraceGenerator.PojoSpan>> traces =
        Collections.singletonList(
            Collections.singletonList(
                generateRandomSpan(InternalSpanTypes.TEST, Collections.emptyMap())));
    PayloadVerifier verifier = new PayloadVerifier(wellKnownTags, traces, mapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(100 << 10, verifier));

    packer.format(traces.get(0), mapper);
    packer.flush();

    verifier.verifyTracesConsumed();
  }

  @Test
  void verifyTestSuiteEndEventIsWrittenCorrectly() {
    Map<String, Object> extraTags = new HashMap<>();
    extraTags.put(Tags.TEST_SESSION_ID, DDTraceId.from(123));
    extraTags.put(Tags.TEST_MODULE_ID, 456L);
    extraTags.put(Tags.TEST_SUITE_ID, 789L);
    TraceGenerator.PojoSpan span = generateRandomSpan(InternalSpanTypes.TEST_SUITE_END, extraTags);

    Map<String, Object> deserializedSpan = whenASpanIsWritten(span);

    verifyTopLevelTags(deserializedSpan, DDTraceId.from(123), 456L, 789L);

    Map<String, Object> spanContent = getContent(deserializedSpan);
    assertFalse(spanContent.containsKey("trace_id"));
    assertFalse(spanContent.containsKey("span_id"));
    assertFalse(spanContent.containsKey("parent_id"));
  }

  @Test
  void verifyTestModuleEndEventIsWrittenCorrectly() {
    Map<String, Object> extraTags = new HashMap<>();
    extraTags.put(Tags.TEST_SESSION_ID, DDTraceId.from(123));
    extraTags.put(Tags.TEST_MODULE_ID, 456L);
    TraceGenerator.PojoSpan span = generateRandomSpan(InternalSpanTypes.TEST_MODULE_END, extraTags);

    Map<String, Object> deserializedSpan = whenASpanIsWritten(span);

    verifyTopLevelTags(deserializedSpan, DDTraceId.from(123), 456L, null);

    Map<String, Object> spanContent = getContent(deserializedSpan);
    assertFalse(spanContent.containsKey("trace_id"));
    assertFalse(spanContent.containsKey("span_id"));
    assertFalse(spanContent.containsKey("parent_id"));
  }

  @Test
  void verifyResultIsNotAffectedBySuccessiveMappingCalls() {
    Map<String, Object> extraTags = new HashMap<>();
    extraTags.put(Tags.TEST_SESSION_ID, DDTraceId.from(123));
    extraTags.put(Tags.TEST_MODULE_ID, 456L);
    extraTags.put(Tags.TEST_SUITE_ID, 789L);
    TraceGenerator.PojoSpan span = generateRandomSpan(InternalSpanTypes.TEST, extraTags);

    whenASpanIsWritten(span);
    Map<String, Object> deserializedSpan = whenASpanIsWritten(span);

    verifyTopLevelTags(deserializedSpan, DDTraceId.from(123), 456L, 789L);

    Map<String, Object> spanContent = getContent(deserializedSpan);
    assertTrue(spanContent.containsKey("trace_id"));
    assertTrue(spanContent.containsKey("span_id"));
    assertTrue(spanContent.containsKey("parent_id"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getContent(Map<String, Object> deserializedSpan) {
    return (Map<String, Object>) deserializedSpan.get("content");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getMetrics(Map<String, Object> spanContent) {
    return (Map<String, Object>) spanContent.get("metrics");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getMeta(Map<String, Object> spanContent) {
    return (Map<String, Object>) spanContent.get("meta");
  }

  private static void verifyTopLevelTags(
      Map<String, Object> deserializedSpan,
      DDTraceId testSessionId,
      Long testModuleId,
      Long testSuiteId) {
    Map<String, Object> spanContent = getContent(deserializedSpan);
    Map<String, Object> deserializedMetrics = getMetrics(spanContent);
    Map<String, Object> deserializedMeta = getMeta(spanContent);

    if (testSessionId != null) {
      assertEquals(
          testSessionId.toLong(), ((Number) spanContent.get(Tags.TEST_SESSION_ID)).longValue());
    } else {
      assertFalse(spanContent.containsKey(Tags.TEST_SESSION_ID));
    }

    if (testModuleId != null) {
      assertEquals(
          testModuleId.longValue(), ((Number) spanContent.get(Tags.TEST_MODULE_ID)).longValue());
    } else {
      assertFalse(spanContent.containsKey(Tags.TEST_MODULE_ID));
    }

    if (testSuiteId != null) {
      assertEquals(
          testSuiteId.longValue(), ((Number) spanContent.get(Tags.TEST_SUITE_ID)).longValue());
    } else {
      assertFalse(spanContent.containsKey(Tags.TEST_SUITE_ID));
    }

    assertFalse(deserializedMetrics.containsKey(Tags.TEST_SESSION_ID));
    assertFalse(deserializedMetrics.containsKey(Tags.TEST_MODULE_ID));
    assertFalse(deserializedMetrics.containsKey(Tags.TEST_SUITE_ID));

    assertFalse(deserializedMeta.containsKey(Tags.TEST_SESSION_ID));
    assertFalse(deserializedMeta.containsKey(Tags.TEST_MODULE_ID));
    assertFalse(deserializedMeta.containsKey(Tags.TEST_SUITE_ID));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> whenASpanIsWritten(TraceGenerator.PojoSpan span) {
    List<TraceGenerator.PojoSpan> trace = Collections.singletonList(span);

    CiVisibilityWellKnownTags wellKnownTags =
        new CiVisibilityWellKnownTags(
            "runtimeid",
            "my-env",
            "language",
            "my-runtime-name",
            "my-runtime-version",
            "my-runtime-vendor",
            "my-os-arch",
            "my-os-platform",
            "my-os-version",
            "false");
    CiTestCycleMapperV1 mapper = new CiTestCycleMapperV1(wellKnownTags, false);

    CaptureConsumer consumer = new CaptureConsumer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(100 << 10, consumer));

    packer.format(trace, mapper);
    packer.flush();

    ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory());
    try {
      return (Map<String, Object>) objectMapper.readValue(consumer.bytes, Object.class);
    } catch (IOException e) {
      fail("Failed to deserialize span: " + e.getMessage());
      return null;
    }
  }

  private static String repeat(String s, int count) {
    StringBuilder sb = new StringBuilder(s.length() * count);
    for (int i = 0; i < count; i++) {
      sb.append(s);
    }
    return sb.toString();
  }

  private static void assertEqualsWithNullAsEmpty(CharSequence expected, CharSequence actual) {
    if (null == expected) {
      assertEquals("", actual);
    } else {
      assertEquals(expected.toString(), actual.toString());
    }
  }

  private static final class CaptureConsumer implements ByteBufferConsumer {
    private byte[] bytes;

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      this.bytes = new byte[buffer.limit() - buffer.position()];
      buffer.get(bytes);
    }
  }

  private static final class PayloadVerifier implements ByteBufferConsumer, WritableByteChannel {

    private final List<List<TraceGenerator.PojoSpan>> expectedTraces;
    private final CiTestCycleMapperV1 mapper;
    private final CiVisibilityWellKnownTags wellKnownTags;
    private ByteBuffer captured = ByteBuffer.allocate(200 << 10);

    private int position = 0;

    private PayloadVerifier(
        CiVisibilityWellKnownTags wellKnownTags,
        List<List<TraceGenerator.PojoSpan>> traces,
        CiTestCycleMapperV1 mapper) {
      this.expectedTraces = traces;
      this.mapper = mapper;
      this.wellKnownTags = wellKnownTags;
    }

    void skipLargeTrace() {
      ++position;
    }

    void verifyTracesConsumed() {
      assertEquals(expectedTraces.size(), position);
    }

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      if (expectedTraces.isEmpty() && messageCount == 0) {
        return;
      }

      try {
        Payload payload = mapper.newPayload().withBody(messageCount, buffer);
        payload.writeTo(this);
        captured.flip();
        assertNotNull(payload.toRequest());
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(captured);
        assertEquals(3, unpacker.unpackMapHeader());
        assertEquals("version", unpacker.unpackString());
        assertEquals(1, unpacker.unpackInt());
        assertEquals("metadata", unpacker.unpackString());
        assertEquals(1, unpacker.unpackMapHeader());
        assertEquals("*", unpacker.unpackString());

        assertEquals(10, unpacker.unpackMapHeader());
        assertEquals("env", unpacker.unpackString());
        assertEquals(
            truncate(wellKnownTags.getEnv().toString(), MAX_META_STRING_VALUE_LENGTH),
            unpacker.unpackString());
        assertEquals("runtime-id", unpacker.unpackString());
        assertEquals(
            truncate(wellKnownTags.getRuntimeId().toString(), MAX_META_STRING_VALUE_LENGTH),
            unpacker.unpackString());
        assertEquals("language", unpacker.unpackString());
        assertEquals(
            truncate(wellKnownTags.getLanguage().toString(), MAX_META_STRING_VALUE_LENGTH),
            unpacker.unpackString());
        assertEquals(Tags.RUNTIME_NAME, unpacker.unpackString());
        assertEquals(
            truncate(wellKnownTags.getRuntimeName().toString(), MAX_META_STRING_VALUE_LENGTH),
            unpacker.unpackString());
        assertEquals(Tags.RUNTIME_VENDOR, unpacker.unpackString());
        assertEquals(
            truncate(wellKnownTags.getRuntimeVendor().toString(), MAX_META_STRING_VALUE_LENGTH),
            unpacker.unpackString());
        assertEquals(Tags.RUNTIME_VERSION, unpacker.unpackString());
        assertEquals(
            truncate(wellKnownTags.getRuntimeVersion().toString(), MAX_META_STRING_VALUE_LENGTH),
            unpacker.unpackString());
        assertEquals(Tags.OS_ARCHITECTURE, unpacker.unpackString());
        assertEquals(
            truncate(wellKnownTags.getOsArch().toString(), MAX_META_STRING_VALUE_LENGTH),
            unpacker.unpackString());
        assertEquals(Tags.OS_PLATFORM, unpacker.unpackString());
        assertEquals(
            truncate(wellKnownTags.getOsPlatform().toString(), MAX_META_STRING_VALUE_LENGTH),
            unpacker.unpackString());
        assertEquals(Tags.OS_VERSION, unpacker.unpackString());
        assertEquals(
            truncate(wellKnownTags.getOsVersion().toString(), MAX_META_STRING_VALUE_LENGTH),
            unpacker.unpackString());
        assertEquals(DDTags.TEST_IS_USER_PROVIDED_SERVICE, unpacker.unpackString());
        assertEquals(
            truncate(
                wellKnownTags.getIsUserProvidedService().toString(), MAX_META_STRING_VALUE_LENGTH),
            unpacker.unpackString());

        assertEquals("events", unpacker.unpackString());

        List<TraceGenerator.PojoSpan> expectedTrace = expectedTraces.get(position++);
        int eventCount = unpacker.unpackArrayHeader();
        while (expectedTrace.size() < eventCount) {
          expectedTrace.addAll(expectedTraces.get(position++));
        }
        assertEquals(expectedTrace.size(), eventCount);
        for (int k = 0; k < eventCount; ++k) {
          TraceGenerator.PojoSpan expectedSpan = expectedTrace.get(k);
          assertEquals(3, unpacker.unpackMapHeader());
          assertEquals("type", unpacker.unpackString());
          if ("test".equals(String.valueOf(expectedSpan.getType()))) {
            assertEquals("test", unpacker.unpackString());
          } else {
            assertEquals("span", unpacker.unpackString());
          }
          assertEquals("version", unpacker.unpackString());
          assertEquals(1, unpacker.unpackInt());
          assertEquals("content", unpacker.unpackString());
          assertEquals(11, unpacker.unpackMapHeader());
          assertEquals("trace_id", unpacker.unpackString());
          long traceId = unpacker.unpackValue().asNumberValue().toLong();
          assertEquals(expectedSpan.getTraceId().toLong(), traceId);
          assertEquals("span_id", unpacker.unpackString());
          long spanId = unpacker.unpackValue().asNumberValue().toLong();
          assertEquals(expectedSpan.getSpanId(), spanId);
          assertEquals("parent_id", unpacker.unpackString());
          long parentId = unpacker.unpackValue().asNumberValue().toLong();
          assertEquals(expectedSpan.getParentId(), parentId);
          assertEquals("service", unpacker.unpackString());
          String serviceName = unpacker.unpackString();
          assertEqualsWithNullAsEmpty(expectedSpan.getServiceName(), serviceName);
          assertEquals("name", unpacker.unpackString());
          String operationName = unpacker.unpackString();
          assertEqualsWithNullAsEmpty(expectedSpan.getOperationName(), operationName);
          assertEquals("resource", unpacker.unpackString());
          String resourceName = unpacker.unpackString();
          assertEqualsWithNullAsEmpty(expectedSpan.getResourceName(), resourceName);

          assertEquals("start", unpacker.unpackString());
          long startTime = unpacker.unpackLong();
          assertEquals(expectedSpan.getStartTime(), startTime);
          assertEquals("duration", unpacker.unpackString());
          long duration = unpacker.unpackLong();
          assertEquals(expectedSpan.getDurationNano(), duration);
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
            if (format == NEGFIXINT
                || format == POSFIXINT
                || format == INT8
                || format == UINT8
                || format == INT16
                || format == UINT16
                || format == INT32
                || format == UINT32) {
              n = unpacker.unpackInt();
            } else if (format == INT64 || format == UINT64) {
              n = unpacker.unpackLong();
            } else if (format == FLOAT32) {
              n = unpacker.unpackFloat();
            } else if (format == FLOAT64) {
              n = unpacker.unpackDouble();
            } else {
              fail("Unexpected type in metrics values: " + format);
            }
            if (DD_MEASURED.toString().equals(key)) {
              assertTrue(
                  (n != null && n.intValue() == 1 && expectedSpan.isMeasured())
                      || !expectedSpan.isMeasured());
            } else if (DDSpanContext.PRIORITY_SAMPLING_KEY.equals(key)) {
              // check that priority sampling is only on first and last span
              if (k == 0 || k == eventCount - 1) {
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
              assertEquals(expectedSpan.getTag(metric.getKey()), metric.getValue());
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
            } else {
              Object tag = expectedSpan.getTag(entry.getKey());
              if (null != tag) {
                assertEquals(String.valueOf(tag), entry.getValue());
              } else {
                assertEquals(expectedSpan.getBaggage().get(entry.getKey()), entry.getValue());
              }
            }
          }
        }
      } catch (IOException e) {
        fail(e.getMessage());
      } finally {
        mapper.reset();
        captured.position(0);
        captured.limit(captured.capacity());
      }
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
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

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void close() throws IOException {}
  }
}
