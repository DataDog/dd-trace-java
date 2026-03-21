package datadog.trace.common.writer.ddagent;

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
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
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.TraceGenerator;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.test.DDCoreSpecification;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

class TraceMapperV05PayloadTest extends DDCoreSpecification {

  @Test
  void bodyOverflowCausesAFlush() {
    // disable process tags since they are only on the first span of the chunk
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false");
    ProcessTags.reset(datadog.trace.api.Config.get());
    try {
      // 4x 36 ASCII characters and 2 bytes of msgpack string prefix
      int dictionarySpacePerTrace = 4 * (36 + 2);
      // enough space for two traces with distinct string values, plus the header
      int dictionarySize = dictionarySpacePerTrace * 2 + 5;
      TraceMapperV0_5 traceMapper = new TraceMapperV0_5(dictionarySize);
      List<TraceGenerator.PojoSpan> repeatedTrace =
          Collections.singletonList(
              new TraceGenerator.PojoSpan(
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
                  (int) PrioritySampling.UNSET,
                  0,
                  null));
      int traceSize = calculateSize(repeatedTrace);
      // 30KB body
      int bufferSize = 30 << 10;
      int tracesRequiredToOverflowBody = (int) Math.ceil(((double) bufferSize) / traceSize) + 1;
      List<List<TraceGenerator.PojoSpan>> traces = new ArrayList<>(tracesRequiredToOverflowBody);
      for (int i = 0; i < tracesRequiredToOverflowBody; ++i) {
        traces.add(repeatedTrace);
      }
      // the last one won't be flushed
      List<List<TraceGenerator.PojoSpan>> flushedTraces = new ArrayList<>(traces);
      flushedTraces.remove(traces.size() - 1);
      // need space for the overflowing buffer, the dictionary, and two small array headers
      PayloadVerifier verifier =
          new PayloadVerifier(flushedTraces, traceMapper, bufferSize + dictionarySize + 1 + 1 + 5);
      MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(bufferSize, verifier));

      for (List<TraceGenerator.PojoSpan> trace : traces) {
        packer.format(trace, traceMapper);
      }

      verifier.verifyTracesConsumed();
    } finally {
      injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "true");
      ProcessTags.reset(datadog.trace.api.Config.get());
    }
  }

  static Stream<Arguments> testDictionaryCompressedTracesWrittenCorrectlyArguments() {
    return Stream.of(
        Arguments.of(10 << 10, 10 << 10, 0, true),
        Arguments.of(10 << 10, 10 << 10, 1, true),
        Arguments.of(10 << 10, 10 << 10, 10, true),
        Arguments.of(10 << 10, 10 << 10, 100, true),
        Arguments.of(10 << 10, 100 << 10, 1, true),
        Arguments.of(10 << 10, 100 << 10, 10, true),
        Arguments.of(10 << 10, 100 << 10, 100, true),
        Arguments.of(10 << 10, 10 << 10, 0, false),
        Arguments.of(10 << 10, 10 << 10, 1, false),
        Arguments.of(10 << 10, 10 << 10, 10, false),
        Arguments.of(10 << 10, 10 << 10, 100, false),
        Arguments.of(10 << 10, 100 << 10, 1, false),
        Arguments.of(10 << 10, 100 << 10, 10, false),
        Arguments.of(10 << 10, 100 << 10, 100, false),
        Arguments.of(100 << 10, 10 << 10, 0, true),
        Arguments.of(100 << 10, 10 << 10, 1, true),
        Arguments.of(100 << 10, 10 << 10, 10, true),
        Arguments.of(100 << 10, 10 << 10, 100, true),
        Arguments.of(100 << 10, 100 << 10, 1, true),
        Arguments.of(100 << 10, 100 << 10, 10, true),
        Arguments.of(100 << 10, 100 << 10, 100, true),
        Arguments.of(100 << 10, 10 << 10, 0, false),
        Arguments.of(100 << 10, 10 << 10, 1, false),
        Arguments.of(100 << 10, 10 << 10, 10, false),
        Arguments.of(100 << 10, 10 << 10, 100, false),
        Arguments.of(100 << 10, 100 << 10, 1, false),
        Arguments.of(100 << 10, 100 << 10, 10, false),
        Arguments.of(100 << 10, 100 << 10, 100, false),
        Arguments.of(100 << 10, 100 << 10, 1000, false));
  }

  @ParameterizedTest
  @MethodSource("testDictionaryCompressedTracesWrittenCorrectlyArguments")
  void testDictionaryCompressedTracesWrittenCorrectly(
      int bufferSize, int dictionarySize, int traceCount, boolean lowCardinality) throws Exception {
    @SuppressWarnings("unchecked")
    List<List<TraceGenerator.PojoSpan>> traces =
        (List<List<TraceGenerator.PojoSpan>>)
            (List<?>) generateRandomTraces(traceCount, lowCardinality);
    TraceMapperV0_5 traceMapper = new TraceMapperV0_5(dictionarySize);
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(bufferSize, verifier));
    boolean tracesFitInBuffer = true;
    for (List<TraceGenerator.PojoSpan> trace : traces) {
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

  @Test
  void testProcessTagsSerialization() throws Exception {
    assertNotNull(ProcessTags.getTagsForSerialization());
    List<TraceGenerator.PojoSpan> spans = new ArrayList<>();
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
    TraceMapperV0_5 traceMapper = new TraceMapperV0_5();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier));

    packer.format(spans, traceMapper);
    packer.flush();

    verifier.verifyTracesConsumed();
  }

  private static final class PayloadVerifier implements ByteBufferConsumer, WritableByteChannel {

    private final List<List<TraceGenerator.PojoSpan>> expectedTraces;
    private final TraceMapperV0_5 mapper;
    private ByteBuffer captured;

    private int position = 0;

    private PayloadVerifier(List<List<TraceGenerator.PojoSpan>> traces, TraceMapperV0_5 mapper) {
      this(traces, mapper, 200 << 10);
    }

    private PayloadVerifier(
        List<List<TraceGenerator.PojoSpan>> traces, TraceMapperV0_5 mapper, int size) {
      this.expectedTraces = traces;
      this.mapper = mapper;
      this.captured = ByteBuffer.allocate(size);
    }

    void skipLargeTrace() {
      ++position;
    }

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      int processTagsCount = 0;
      try {
        Payload payload = mapper.newPayload().withBody(messageCount, buffer);
        payload.writeTo(this);
        captured.flip();
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(captured);
        int header = unpacker.unpackArrayHeader();
        assertEquals(2, header);
        int dictionarySize = unpacker.unpackArrayHeader();
        String[] dictionary = new String[dictionarySize];
        for (int i = 0; i < dictionary.length; ++i) {
          dictionary[i] = unpacker.unpackString();
        }
        int traceCount = unpacker.unpackArrayHeader();
        for (int i = 0; i < traceCount; ++i) {
          List<TraceGenerator.PojoSpan> expectedTrace = expectedTraces.get(position++);
          int spanCount = unpacker.unpackArrayHeader();
          assertEquals(expectedTrace.size(), spanCount);
          for (int k = 0; k < spanCount; ++k) {
            TraceGenerator.PojoSpan expectedSpan = expectedTrace.get(k);
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
              } else {
                Object tag = expectedSpan.getTag(entry.getKey());
                if (null != tag) {
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
                  Assert.fail("Unexpected type in metrics values: " + format + " for key " + key);
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
                    0.001,
                    metric.getKey());
              } else {
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
        Assert.fail(e.getMessage());
      } finally {
        assertEquals(
            Config.get().isExperimentalPropagateProcessTagsEnabled() ? 1 : 0, processTagsCount);
        mapper.reset();
        captured.position(0);
        captured.limit(captured.capacity());
      }
    }

    @Override
    public int write(ByteBuffer src) {
      if (captured.remaining() < src.remaining()) {
        ByteBuffer newBuffer = ByteBuffer.allocate(captured.capacity() + src.remaining());
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

  static int calculateSize(List<TraceGenerator.PojoSpan> trace) {
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
    packer.format(trace, new TraceMapperV0_5(1024));
    packer.flush();
    return size.get();
  }
}
