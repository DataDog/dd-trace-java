package datadog.trace.common.writer.ddagent;

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
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
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.sampling.PrioritySampling;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

@ExtendWith(WithConfigExtension.class)
class TraceMapperV05PayloadTest {

  // Keep the ProcessTags static in sync with the (per-test rebuilt) Config, the way DDSpecification
  // did for the original Spock tests. Runs after WithConfigExtension has rebuilt Config.
  @BeforeEach
  void syncProcessTags() {
    ProcessTags.reset(Config.get());
  }

  // A method-level @WithConfig below disables process tags, but WithConfigExtension only rebuilds
  // Config after each test -- it does not reset the ProcessTags static. Reset it once the class is
  // done so a disabled state can't leak into other tests sharing this Gradle JVM. By afterAll the
  // extension has already restored Config to its defaults.
  @AfterAll
  static void resetProcessTags() {
    ProcessTags.reset(Config.get());
  }

  // disable process tags since they are only on the first span of the chunk otherwise the
  // calculation woes
  @Test
  @WithConfig(key = EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, value = "false")
  void bodyOverflowCausesAFlush() {
    // 4x 36 ASCII characters and 2 bytes of msgpack string prefix
    int dictionarySpacePerTrace = 4 * (36 + 2);
    // enough space for two traces with distinct string values, plus the header
    int dictionarySize = dictionarySpacePerTrace * 2 + 5;
    TraceMapperV0_5 traceMapper = new TraceMapperV0_5(dictionarySize);
    List<PojoSpan> repeatedTrace =
        Collections.singletonList(
            new PojoSpan(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                DDTraceId.ZERO,
                DDSpanId.ZERO,
                DDSpanId.ZERO,
                10000,
                100,
                0,
                Collections.emptyMap(),
                Collections.emptyMap(),
                UUID.randomUUID().toString(),
                false,
                PrioritySampling.UNSET,
                0,
                null));
    // The first trace of a payload is larger: it carries the payload-scoped _dd.sdk.otlp_export
    // marker on its first span. Size both cases so the overflow point is exact.
    int firstTraceSize = calculateSize(repeatedTrace, true);
    int traceSize = calculateSize(repeatedTrace, false);
    // 30KB body
    int bufferSize = 30 << 10;
    int tracesThatFitInBody = 1 + (bufferSize - firstTraceSize) / traceSize;
    int tracesRequiredToOverflowBody = tracesThatFitInBody + 1;
    List<List<PojoSpan>> traces = new ArrayList<>(tracesRequiredToOverflowBody);
    for (int i = 0; i < tracesRequiredToOverflowBody; ++i) {
      traces.add(repeatedTrace);
    }
    // the last one won't be flushed
    List<List<PojoSpan>> flushedTraces = new ArrayList<>(traces);
    flushedTraces.remove(traces.size() - 1);
    // need space for the overflowing buffer, the dictionary, and two small array headers
    PayloadVerifier verifier =
        new PayloadVerifier(flushedTraces, traceMapper, bufferSize + dictionarySize + 1 + 1 + 5);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(bufferSize, verifier));
    for (List<PojoSpan> trace : traces) {
      packer.format(trace, traceMapper);
    }
    verifier.verifyTracesConsumed();
  }

  @ParameterizedTest(name = "buffer={0} dict={1} traces={2} lowCardinality={3}")
  @MethodSource("dictionaryCompressedTracesWrittenCorrectlyArguments")
  void dictionaryCompressedTracesWrittenCorrectly(
      int bufferSize, int dictionarySize, int traceCount, boolean lowCardinality) {
    List<List<PojoSpan>> traces = generateRandomTraces(traceCount, lowCardinality);
    TraceMapperV0_5 traceMapper = new TraceMapperV0_5(dictionarySize);
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

  private static Stream<Arguments> dictionaryCompressedTracesWrittenCorrectlyArguments() {
    return Stream.of(
        arguments(10 << 10, 10 << 10, 0, true),
        arguments(10 << 10, 10 << 10, 1, true),
        arguments(10 << 10, 10 << 10, 10, true),
        arguments(10 << 10, 10 << 10, 100, true),
        arguments(10 << 10, 100 << 10, 1, true),
        arguments(10 << 10, 100 << 10, 10, true),
        arguments(10 << 10, 100 << 10, 100, true),
        arguments(10 << 10, 10 << 10, 0, false),
        arguments(10 << 10, 10 << 10, 1, false),
        arguments(10 << 10, 10 << 10, 10, false),
        arguments(10 << 10, 10 << 10, 100, false),
        arguments(10 << 10, 100 << 10, 1, false),
        arguments(10 << 10, 100 << 10, 10, false),
        arguments(10 << 10, 100 << 10, 100, false),
        arguments(100 << 10, 10 << 10, 0, true),
        arguments(100 << 10, 10 << 10, 1, true),
        arguments(100 << 10, 10 << 10, 10, true),
        arguments(100 << 10, 10 << 10, 100, true),
        arguments(100 << 10, 100 << 10, 1, true),
        arguments(100 << 10, 100 << 10, 10, true),
        arguments(100 << 10, 100 << 10, 100, true),
        arguments(100 << 10, 10 << 10, 0, false),
        arguments(100 << 10, 10 << 10, 1, false),
        arguments(100 << 10, 10 << 10, 10, false),
        arguments(100 << 10, 10 << 10, 100, false),
        arguments(100 << 10, 100 << 10, 1, false),
        arguments(100 << 10, 100 << 10, 10, false),
        arguments(100 << 10, 100 << 10, 1000, false));
  }

  @Test
  void processTagsSerialization() {
    assertNotNull(ProcessTags.getTagsForSerialization());
    List<PojoSpan> spans = new ArrayList<>();
    for (long spanId = 1; spanId <= 2; ++spanId) {
      spans.add(plainSpan(spanId));
    }

    List<List<PojoSpan>> traces = Collections.singletonList(spans);
    TraceMapperV0_5 traceMapper = new TraceMapperV0_5();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier));

    packer.format(spans, traceMapper);
    packer.flush();

    verifier.verifyTracesConsumed();
  }

  /**
   * v0.5 has no payload-level field, so the export-mode marker rides on the first span of the first
   * non-empty chunk and the Agent hoists it onto {@code TracerPayload.tags}.
   */
  @Test
  void otlpExportMarkerOnlyOnFirstSpanOfFirstNonEmptyChunk() {
    List<List<PojoSpan>> traces =
        Arrays.asList(
            Collections.emptyList(),
            Arrays.asList(plainSpan(1), plainSpan(2)),
            Collections.singletonList(plainSpan(3)));
    TraceMapperV0_5 traceMapper = new TraceMapperV0_5();
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
    TraceMapperV0_5 traceMapper = new TraceMapperV0_5();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper).expectOtlpExport("true");
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(200 << 10, verifier));

    packer.format(traces.get(0), traceMapper);
    packer.flush();

    verifier.verifyTracesConsumed();
    assertEquals(0, verifier.otlpExportTraceIndex());
  }

  private static PojoSpan plainSpan(long spanId) {
    return new PojoSpan(
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
        "origin");
  }

  private static final class PayloadVerifier implements ByteBufferConsumer {

    private final List<List<PojoSpan>> expectedTraces;
    private final TraceMapperV0_5 mapper;
    private final PayloadVerifiers.CapturingChannel channel;

    private int position = 0;

    /** Expected value of the payload-scoped {@code _dd.sdk.otlp_export} marker. */
    private String expectedOtlpExport = "false";

    /**
     * Payload-spanning index of the chunk the marker was last seen on, or {@code -1} if it was
     * never seen. The span index within that chunk is not tracked: {@link #accept} already asserts
     * the marker only ever rides span 0.
     */
    private int otlpExportTraceIndex = -1;

    private PayloadVerifier(List<List<PojoSpan>> traces, TraceMapperV0_5 mapper) {
      this(traces, mapper, 200 << 10);
    }

    private PayloadVerifier(List<List<PojoSpan>> traces, TraceMapperV0_5 mapper, int size) {
      this.expectedTraces = traces;
      this.mapper = mapper;
      this.channel = new PayloadVerifiers.CapturingChannel(size);
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
        int header = unpacker.unpackArrayHeader();
        assertEquals(2, header);
        int dictionarySize = unpacker.unpackArrayHeader();
        String[] dictionary = new String[dictionarySize];
        for (int i = 0; i < dictionary.length; ++i) {
          dictionary[i] = unpacker.unpackString();
        }
        int traceCount = unpacker.unpackArrayHeader();
        for (int i = 0; i < traceCount; ++i) {
          List<PojoSpan> expectedTrace = expectedTraces.get(position++);
          int spanCount = unpacker.unpackArrayHeader();
          assertEquals(expectedTrace.size(), spanCount);
          for (int k = 0; k < spanCount; ++k) {
            PojoSpan expectedSpan = expectedTrace.get(k);
            int elementCount = unpacker.unpackArrayHeader();
            assertEquals(12, elementCount);
            String serviceName = dictionary[unpacker.unpackInt()];
            assertEqualsWithNullAsEmpty(expectedSpan.getServiceName(), serviceName);
            String operationName = dictionary[unpacker.unpackInt()];
            assertEqualsWithNullAsEmpty(expectedSpan.getOperationName(), operationName);
            String resourceName = dictionary[unpacker.unpackInt()];
            assertEqualsWithNullAsEmpty(expectedSpan.getResourceName(), resourceName);
            long traceId = unpacker.unpackValue().asNumberValue().toLong();
            assertEquals(expectedSpan.getTraceId().toLong(), traceId);
            long spanId = unpacker.unpackValue().asNumberValue().toLong();
            assertEquals(expectedSpan.getSpanId(), spanId);
            long parentId = unpacker.unpackValue().asNumberValue().toLong();
            assertEquals(expectedSpan.getParentId(), parentId);
            long startTime = unpacker.unpackLong();
            assertEquals(expectedSpan.getStartTime(), startTime);
            long duration = unpacker.unpackLong();
            assertEquals(expectedSpan.getDurationNano(), duration);
            int error = unpacker.unpackInt();
            assertEquals(expectedSpan.getError(), error);
            int metaSize = unpacker.unpackMapHeader();
            HashMap<String, String> meta = new HashMap<>();
            for (int j = 0; j < metaSize; ++j) {
              meta.put(dictionary[unpacker.unpackInt()], dictionary[unpacker.unpackInt()]);
            }
            for (Map.Entry<String, String> entry : meta.entrySet()) {
              if (Tags.HTTP_STATUS.equals(entry.getKey())) {
                assertEquals(String.valueOf(expectedSpan.getHttpStatusCode()), entry.getValue());
              } else if (DDTags.ORIGIN_KEY.equals(entry.getKey())) {
                assertEquals(expectedSpan.getOrigin(), entry.getValue());
              } else if (DDTags.PROCESS_TAGS.equals(entry.getKey())) {
                processTagsCount++;
                assertTrue(Config.get().isExperimentalPropagateProcessTagsEnabled());
                assertEquals(0, k);
                assertEquals(ProcessTags.getTagsForSerialization().toString(), entry.getValue());
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
            int metricsSize = unpacker.unpackMapHeader();
            HashMap<String, Number> metrics = new HashMap<>();
            for (int j = 0; j < metricsSize; ++j) {
              String key = dictionary[unpacker.unpackInt()];
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
                    0.001,
                    metric.getKey());
              } else {
                // Integer-typed metrics round-trip through msgpack's minimal encoding, so a Long
                // tag can come back as an Integer (and vice versa). Compare numerically.
                assertEquals(
                    ((Number) expectedSpan.getTag(metric.getKey())).longValue(),
                    metric.getValue().longValue(),
                    metric.getKey());
              }
            }
            String type = dictionary[unpacker.unpackInt()];
            assertEquals(expectedSpan.getType(), type);
          }
        }
      } catch (IOException e) {
        fail(e.getMessage());
      } finally {
        // Reset before asserting: a failing assertion here must not leave the mapper and channel
        // dirty for the next payload of a multi-payload test.
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

  /**
   * Serialized size of {@code trace}, either as the first trace of a payload (which carries the
   * payload-scoped markers on its first span) or as any later trace.
   */
  private static int calculateSize(List<PojoSpan> trace, boolean firstInPayload) {
    AtomicInteger size = new AtomicInteger();
    MsgPackWriter packer =
        new MsgPackWriter(
            new FlushingBuffer(
                1024,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {
                    size.set(buffer.limit() - buffer.position());
                  }
                }));
    TraceMapperV0_5 mapper = new TraceMapperV0_5(1024);
    if (!firstInPayload) {
      // burn the payload-scoped markers on a throwaway trace
      packer.format(trace, mapper);
      packer.flush();
    }
    packer.format(trace, mapper);
    packer.flush();
    return size.get();
  }
}
