package datadog.trace.common.writer.ddagent;

import static datadog.trace.api.config.TracerConfig.WRITER_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED;
import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.MULTI_WRITER_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.OTLP_WRITER_TYPE;
import static datadog.trace.common.writer.TraceGenerator.generateRandomTraces;
import static datadog.trace.common.writer.ddagent.PayloadVerifiers.assertEqualsWithNullAsEmpty;
import static datadog.trace.common.writer.ddagent.PayloadVerifiers.unpackNumber;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
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
import datadog.trace.common.writer.TraceGenerator.PojoSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

@ExtendWith(WithConfigExtension.class)
class TraceMapperV04PayloadTest {

  // Keep the ProcessTags static in sync with the (per-test rebuilt) Config, the way DDSpecification
  // did for the original Spock tests. Runs after WithConfigExtension has rebuilt Config.
  @BeforeEach
  void syncProcessTags() {
    ProcessTags.reset(Config.get());
  }

  @ParameterizedTest(name = "buffer={0} traces={1} lowCardinality={2}")
  @MethodSource("tracesWrittenCorrectlyArguments")
  void tracesWrittenCorrectly(int bufferSize, int traceCount, boolean lowCardinality) {
    List<List<PojoSpan>> traces = generateRandomTraces(traceCount, lowCardinality);
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(bufferSize, verifier));
    boolean tracesFitInBuffer = true;
    for (List<PojoSpan> trace : traces) {
      if (!packer.format(trace, traceMapper)) {
        verifier.skipLargeTrace();
        tracesFitInBuffer = false;
      }
    }
    packer.flush();

    if (tracesFitInBuffer) {
      verifier.verifyTracesConsumed();
    }
  }

  private static Stream<Arguments> tracesWrittenCorrectlyArguments() {
    return Stream.of(
        arguments(20 << 10, 0, true),
        arguments(20 << 10, 1, true),
        arguments(30 << 10, 1, true),
        arguments(30 << 10, 2, true),
        arguments(20 << 10, 0, false),
        arguments(20 << 10, 1, false),
        arguments(30 << 10, 1, false),
        arguments(30 << 10, 2, false),
        arguments(100 << 10, 0, true),
        arguments(100 << 10, 1, true),
        arguments(100 << 10, 10, true),
        arguments(100 << 10, 100, true),
        arguments(100 << 10, 1000, true),
        arguments(100 << 10, 0, false),
        arguments(100 << 10, 1, false),
        arguments(100 << 10, 10, false),
        arguments(100 << 10, 100, false),
        arguments(100 << 10, 1000, false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fullSixtyFourBitTraceAndSpanIdentifiersArguments")
  void fullSixtyFourBitTraceAndSpanIdentifiers(
      String scenario, DDTraceId traceId, long spanId, long parentId) {
    PojoSpan span = plainSpan(traceId, spanId, parentId);
    List<List<PojoSpan>> traces = Collections.singletonList(Collections.singletonList(span));
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier));

    packer.format(Collections.singletonList(span), traceMapper);
    packer.flush();

    verifier.verifyTracesConsumed();
  }

  private static Stream<Arguments> fullSixtyFourBitTraceAndSpanIdentifiersArguments() {
    return Stream.of(
        arguments("ONE", DD64bTraceId.ONE, 2L, 3L),
        arguments("MAX", DD64bTraceId.MAX, 2L, 3L),
        arguments("negative", DD64bTraceId.from(-10), -11L, -12L));
  }

  @Test
  void metaStructSupport() {
    PojoSpan span = plainSpan(1L);
    List<Map<String, String>> stack = new ArrayList<>();
    for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
      Map<String, String> frame = new HashMap<>();
      frame.put("file", element.getFileName() != null ? element.getFileName() : "");
      frame.put("class_name", element.getClassName() != null ? element.getClassName() : "");
      frame.put("function", element.getMethodName() != null ? element.getMethodName() : "");
      stack.add(frame);
    }
    span.setMetaStruct("stack", stack);
    List<List<PojoSpan>> traces = Collections.singletonList(Collections.singletonList(span));
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4();
    PayloadVerifier verifier =
        new PayloadVerifier(
            traces,
            traceMapper,
            (expected, received) -> {
              MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(received);
              List<?> expectedStack = (List<?>) expected;
              int size = unpacker.unpackArrayHeader();
              assertEquals(expectedStack.size(), size);
              for (Object entry : expectedStack) {
                @SuppressWarnings("unchecked")
                Map<String, String> stackEntry = (Map<String, String>) entry;
                int fields = unpacker.unpackMapHeader();
                for (int f = 0; f < fields; ++f) {
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
  void processTagsSerialization() {
    assertNotNull(ProcessTags.getTagsForSerialization());
    List<PojoSpan> spans = new ArrayList<>();
    for (long spanId = 1; spanId <= 2; ++spanId) {
      spans.add(plainSpan(spanId));
    }

    List<List<PojoSpan>> traces = Collections.singletonList(spans);
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier));

    packer.format(spans, traceMapper);
    packer.flush();

    verifier.verifyTracesConsumed();
  }

  /**
   * v0.4 has no payload-level field, so the export-mode marker rides on the first span of the first
   * non-empty chunk and the Agent hoists it onto {@code TracerPayload.tags}.
   */
  @Test
  void otlpExportMarkerOnlyOnFirstSpanOfFirstNonEmptyChunk() {
    List<List<PojoSpan>> traces =
        Arrays.asList(
            Collections.emptyList(),
            Arrays.asList(plainSpan(1), plainSpan(2)),
            Collections.singletonList(plainSpan(3)));
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(200 << 10, verifier));

    for (List<PojoSpan> trace : traces) {
      assertTrue(packer.format(trace, traceMapper));
    }
    packer.flush();

    verifier.verifyTracesConsumed();
    // The verifier already asserts exactly one marker per payload, that it sits on span 0 and that
    // it holds the expected value; pin down *which chunk* it landed on: the first NON-EMPTY one,
    // not the leading empty one and not a later one.
    assertEquals(1, verifier.otlpExportTraceIndex());
  }

  @Test
  @WithConfig(
      key = WRITER_TYPE,
      value = MULTI_WRITER_TYPE + ":" + OTLP_WRITER_TYPE + ",DDAgentWriter")
  void otlpExportMarkerIsTrueWhenAlsoExportingOverOtlp() {
    List<List<PojoSpan>> traces =
        Collections.singletonList(Collections.singletonList(plainSpan(1)));
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper).expectOtlpExport("true");
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(200 << 10, verifier));

    packer.format(traces.get(0), traceMapper);
    packer.flush();

    verifier.verifyTracesConsumed();
    assertEquals(0, verifier.otlpExportTraceIndex());
  }

  private static PojoSpan plainSpan(long spanId) {
    return plainSpan(DDTraceId.ONE, spanId, -1L);
  }

  private static PojoSpan plainSpan(DDTraceId traceId, long spanId, long parentId) {
    return new PojoSpan(
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
  }

  private static final class PayloadVerifier implements ByteBufferConsumer {

    private final List<List<PojoSpan>> expectedTraces;
    private final TraceMapperV0_4 mapper;
    private final MetaStructVerifier<Object> metaStructVerifier;
    private final PayloadVerifiers.CapturingChannel channel =
        new PayloadVerifiers.CapturingChannel(200 << 10);

    private int position = 0;

    /** Expected value of the payload-scoped {@code _dd.sdk.otlp_export} marker. */
    private String expectedOtlpExport = "false";

    /**
     * Payload-spanning index of the chunk the marker was last seen on, or {@code -1} if it was
     * never seen. The span index within that chunk is not tracked: {@link #accept} already asserts
     * the marker only ever rides span 0.
     */
    private int otlpExportTraceIndex = -1;

    private PayloadVerifier(List<List<PojoSpan>> traces, TraceMapperV0_4 mapper) {
      this(traces, mapper, null);
    }

    private PayloadVerifier(
        List<List<PojoSpan>> traces,
        TraceMapperV0_4 mapper,
        MetaStructVerifier<Object> metaStructVerifier) {
      this.expectedTraces = traces;
      this.mapper = mapper;
      this.metaStructVerifier = metaStructVerifier;
    }

    /** Sets the expected {@code _dd.sdk.otlp_export} value (defaults to {@code "false"}). */
    PayloadVerifier expectOtlpExport(String value) {
      this.expectedOtlpExport = value;
      return this;
    }

    int otlpExportTraceIndex() {
      return otlpExportTraceIndex;
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
      int otlpExportCount = 0;
      try {
        Payload payload = mapper.newPayload().withBody(messageCount, buffer);
        payload.writeTo(channel);
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(channel.flipForReading());
        int traceCount = unpacker.unpackArrayHeader();
        for (int i = 0; i < traceCount; ++i) {
          List<PojoSpan> expectedTrace = expectedTraces.get(position++);
          int spanCount = unpacker.unpackArrayHeader();
          assertEquals(expectedTrace.size(), spanCount);
          for (int k = 0; k < spanCount; ++k) {
            PojoSpan expectedSpan = expectedTrace.get(k);
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
              Number n = unpackNumber(unpacker, key);
              if (DD_MEASURED.toString().equals(key)) {
                assertTrue(
                    (n.intValue() == 1 && expectedSpan.isMeasured()) || !expectedSpan.isMeasured());
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
                // Integer-typed metrics round-trip through msgpack's minimal encoding, so a Long
                // tag can come back as an Integer (and vice versa). Compare numerically.
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
              } else if (TraceMapper.SDK_OTLP_EXPORT.equals(entry.getKey())) {
                // Payload-scoped: only the first span of the first non-empty chunk carries it.
                otlpExportCount++;
                assertEquals(0, k);
                assertEquals(expectedOtlpExport, entry.getValue());
                // `position` was post-incremented when this trace was picked up, so the current
                // trace's payload-spanning index is `position - 1`.
                otlpExportTraceIndex = position - 1;
              } else {
                Object tag = expectedSpan.getTag(entry.getKey());
                if (tag != null) {
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
        fail(e.getMessage());
      } finally {
        mapper.reset();
        channel.resetForWriting();
        assertEquals(
            Config.get().isExperimentalPropagateProcessTagsEnabled() ? 1 : 0, processTagsCount);
        // exactly one _dd.sdk.otlp_export per payload, never per span
        assertEquals(1, otlpExportCount);
      }
    }

    void verifyTracesConsumed() {
      assertEquals(expectedTraces.size(), position);
    }
  }

  @FunctionalInterface
  private interface MetaStructVerifier<E> {
    void verify(E expected, byte[] received) throws IOException;
  }
}
