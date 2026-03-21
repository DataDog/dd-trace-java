package datadog.trace.civisibility.writer.ddintake;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED;
import static datadog.trace.common.writer.TraceGenerator.generateRandomSpan;
import static datadog.trace.common.writer.TraceGenerator.generateRandomTraces;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.jackson.dataformat.MessagePackFactory;

class CiTestCycleMapperV1PayloadTest {

  @ParameterizedTest
  @CsvSource({
    "20480,  0, true",
    "20480,  1, true",
    "30720,  1, true",
    "30720,  2, true",
    "20480,  0, false",
    "20480,  1, false",
    "30720,  1, false",
    "30720,  2, false",
    "102400, 0, true",
    "102400, 1, true",
    "102400, 10, true",
    "102400, 100, true",
    "102400, 1000, true",
    "102400, 0, false",
    "102400, 1, false",
    "102400, 10, false",
    "102400, 100, false",
    "102400, 1000, false"
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

    @SuppressWarnings("unchecked")
    List<List<TraceGenerator.PojoSpan>> traces =
        (List<List<TraceGenerator.PojoSpan>>)
            (List<?>) generateRandomTraces(traceCount, lowCardinality);
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
    Map<String, Object> spanTags = new HashMap<>();
    spanTags.put(Tags.TEST_SESSION_ID, DDTraceId.from(123));
    spanTags.put(Tags.TEST_MODULE_ID, 456L);
    spanTags.put(Tags.TEST_SUITE_ID, 789L);
    TraceGenerator.PojoSpan span =
        (TraceGenerator.PojoSpan) generateRandomSpan(InternalSpanTypes.TEST, spanTags);

    Map<String, Object> deserializedSpan = whenASpanIsWritten(span);

    verifyTopLevelTags(deserializedSpan, DDTraceId.from(123), 456L, 789L);

    Map<String, Object> spanContent = (Map<String, Object>) deserializedSpan.get("content");
    assert spanContent.containsKey("trace_id");
    assert spanContent.containsKey("span_id");
    assert spanContent.containsKey("parent_id");
  }

  @Test
  void verifyTestSuiteEndEventIsWrittenCorrectly() {
    Map<String, Object> spanTags = new HashMap<>();
    spanTags.put(Tags.TEST_SESSION_ID, DDTraceId.from(123));
    spanTags.put(Tags.TEST_MODULE_ID, 456L);
    spanTags.put(Tags.TEST_SUITE_ID, 789L);
    TraceGenerator.PojoSpan span =
        (TraceGenerator.PojoSpan) generateRandomSpan(InternalSpanTypes.TEST_SUITE_END, spanTags);

    Map<String, Object> deserializedSpan = whenASpanIsWritten(span);

    verifyTopLevelTags(deserializedSpan, DDTraceId.from(123), 456L, 789L);

    Map<String, Object> spanContent = (Map<String, Object>) deserializedSpan.get("content");
    assert !spanContent.containsKey("trace_id");
    assert !spanContent.containsKey("span_id");
    assert !spanContent.containsKey("parent_id");
  }

  @Test
  void verifyTestModuleEndEventIsWrittenCorrectly() {
    Map<String, Object> spanTags = new HashMap<>();
    spanTags.put(Tags.TEST_SESSION_ID, DDTraceId.from(123));
    spanTags.put(Tags.TEST_MODULE_ID, 456L);
    TraceGenerator.PojoSpan span =
        (TraceGenerator.PojoSpan) generateRandomSpan(InternalSpanTypes.TEST_MODULE_END, spanTags);

    Map<String, Object> deserializedSpan = whenASpanIsWritten(span);

    verifyTopLevelTags(deserializedSpan, DDTraceId.from(123), 456L, null);

    Map<String, Object> spanContent = (Map<String, Object>) deserializedSpan.get("content");
    assert !spanContent.containsKey("trace_id");
    assert !spanContent.containsKey("span_id");
    assert !spanContent.containsKey("parent_id");
  }

  @Test
  void verifyResultIsNotAffectedBySuccessiveMappingCalls() {
    Map<String, Object> spanTags = new HashMap<>();
    spanTags.put(Tags.TEST_SESSION_ID, DDTraceId.from(123));
    spanTags.put(Tags.TEST_MODULE_ID, 456L);
    spanTags.put(Tags.TEST_SUITE_ID, 789L);
    TraceGenerator.PojoSpan span =
        (TraceGenerator.PojoSpan) generateRandomSpan(InternalSpanTypes.TEST, spanTags);

    whenASpanIsWritten(span);
    Map<String, Object> deserializedSpan = whenASpanIsWritten(span);

    verifyTopLevelTags(deserializedSpan, DDTraceId.from(123), 456L, 789L);

    Map<String, Object> spanContent = (Map<String, Object>) deserializedSpan.get("content");
    assert spanContent.containsKey("trace_id");
    assert spanContent.containsKey("span_id");
    assert spanContent.containsKey("parent_id");
  }

  private static void verifyTopLevelTags(
      Map<String, Object> deserializedSpan,
      DDTraceId testSessionId,
      Long testModuleId,
      Long testSuiteId) {
    Map<String, Object> deserializedSpanContent =
        (Map<String, Object>) deserializedSpan.get("content");
    Map<String, Object> deserializedMetrics =
        (Map<String, Object>) deserializedSpanContent.get("metrics");
    Map<String, Object> deserializedMeta =
        (Map<String, Object>) deserializedSpanContent.get("meta");

    if (testSessionId != null) {
      assertEquals(
          testSessionId.toLong(),
          ((Number) deserializedSpanContent.get(Tags.TEST_SESSION_ID)).longValue());
    } else {
      assert !deserializedSpanContent.containsKey(Tags.TEST_SESSION_ID);
    }

    if (testModuleId != null) {
      assertEquals(
          testModuleId, ((Number) deserializedSpanContent.get(Tags.TEST_MODULE_ID)).longValue());
    } else {
      assert !deserializedSpanContent.containsKey(Tags.TEST_MODULE_ID);
    }

    if (testSuiteId != null) {
      assertEquals(
          testSuiteId, ((Number) deserializedSpanContent.get(Tags.TEST_SUITE_ID)).longValue());
    } else {
      assert !deserializedSpanContent.containsKey(Tags.TEST_SUITE_ID);
    }

    assert !deserializedMetrics.containsKey(Tags.TEST_SESSION_ID);
    assert !deserializedMetrics.containsKey(Tags.TEST_MODULE_ID);
    assert !deserializedMetrics.containsKey(Tags.TEST_SUITE_ID);

    assert !deserializedMeta.containsKey(Tags.TEST_SESSION_ID);
    assert !deserializedMeta.containsKey(Tags.TEST_MODULE_ID);
    assert !deserializedMeta.containsKey(Tags.TEST_SUITE_ID);
  }

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
      throw new RuntimeException(e);
    }
  }

  private static class CaptureConsumer implements ByteBufferConsumer {
    private byte[] bytes;

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      bytes = new byte[buffer.limit() - buffer.position()];
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
        assertEquals(wellKnownTags.getEnv().toString(), unpacker.unpackString());
        assertEquals("runtime-id", unpacker.unpackString());
        assertEquals(wellKnownTags.getRuntimeId().toString(), unpacker.unpackString());
        assertEquals("language", unpacker.unpackString());
        assertEquals(wellKnownTags.getLanguage().toString(), unpacker.unpackString());
        assertEquals(Tags.RUNTIME_NAME, unpacker.unpackString());
        assertEquals(wellKnownTags.getRuntimeName().toString(), unpacker.unpackString());
        assertEquals(Tags.RUNTIME_VENDOR, unpacker.unpackString());
        assertEquals(wellKnownTags.getRuntimeVendor().toString(), unpacker.unpackString());
        assertEquals(Tags.RUNTIME_VERSION, unpacker.unpackString());
        assertEquals(wellKnownTags.getRuntimeVersion().toString(), unpacker.unpackString());
        assertEquals(Tags.OS_ARCHITECTURE, unpacker.unpackString());
        assertEquals(wellKnownTags.getOsArch().toString(), unpacker.unpackString());
        assertEquals(Tags.OS_PLATFORM, unpacker.unpackString());
        assertEquals(wellKnownTags.getOsPlatform().toString(), unpacker.unpackString());
        assertEquals(Tags.OS_VERSION, unpacker.unpackString());
        assertEquals(wellKnownTags.getOsVersion().toString(), unpacker.unpackString());
        assertEquals(DDTags.TEST_IS_USER_PROVIDED_SERVICE, unpacker.unpackString());
        assertEquals(wellKnownTags.getIsUserProvidedService().toString(), unpacker.unpackString());

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
          if ("test".equals(expectedSpan.getType())) {
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
              assert (n.intValue() == 1 && expectedSpan.isMeasured()) || !expectedSpan.isMeasured();
            } else if (DDSpanContext.PRIORITY_SAMPLING_KEY.equals(key)) {
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
        Assertions.fail(e.getMessage());
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

  private static void assertEqualsWithNullAsEmpty(CharSequence expected, CharSequence actual) {
    if (null == expected) {
      assertEquals("", actual);
    } else {
      assertEquals(expected.toString(), actual.toString());
    }
  }
}
