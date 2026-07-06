package datadog.trace.common.writer.ddagent;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED;
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
import datadog.trace.junit.utils.config.WithConfigExtension;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
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
        // in the real like the mapper is always reset each trace.
        // here we need to force it when we fail since the buffer will be reset as well
        traceMapper.reset();
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
    PojoSpan span =
        new PojoSpan(
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
    PojoSpan span =
        new PojoSpan(
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
      spans.add(
          new PojoSpan(
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

    List<List<PojoSpan>> traces = Collections.singletonList(spans);
    TraceMapperV0_4 traceMapper = new TraceMapperV0_4();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier));

    packer.format(spans, traceMapper);
    packer.flush();

    verifier.verifyTracesConsumed();
  }

  private static final class PayloadVerifier implements ByteBufferConsumer {

    private final List<List<PojoSpan>> expectedTraces;
    private final TraceMapperV0_4 mapper;
    private final MetaStructVerifier<Object> metaStructVerifier;
    private final PayloadVerifiers.CapturingChannel channel =
        new PayloadVerifiers.CapturingChannel(200 << 10);

    private int position = 0;

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
