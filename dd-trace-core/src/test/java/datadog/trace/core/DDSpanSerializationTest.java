package datadog.trace.core;

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.ddagent.TraceMapperTestBridge;
import datadog.trace.common.writer.ddagent.TraceMapperV0_4;
import datadog.trace.common.writer.ddagent.TraceMapperV0_5;
import datadog.trace.junit.utils.config.WithConfig;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.core.buffer.ArrayBufferInput;
import org.msgpack.value.ValueType;
import org.tabletest.junit.TableTest;

@WithConfig(key = EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, value = "false")
public class DDSpanSerializationTest extends DDCoreJavaSpecification {

  @BeforeAll
  static void beforeAll() {
    ProcessTags.reset(Config.get());
  }

  @AfterAll
  static void afterAll() {
    ProcessTags.reset(Config.get());
  }

  @TableTest({
    "scenario                   | value                | spanType ",
    "zero                       | 0                    |          ",
    "one with type              | 1                    | some-type",
    "max long minus one billion | 8223372036854775807  |          ",
    "Long.MAX_VALUE minus one   | 9223372036854775806  | some-type",
    "Long.MAX_VALUE plus one    | 9223372036854775808  |          ",
    "2^64 minus one             | 18446744073709551615 | some-type"
  })
  void serializeTraceWithIdAsInt(String scenario, String value, String spanType) throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    DDTraceId traceId = DDTraceId.from(value);
    long spanId = DDSpanId.from(value);
    DDSpanContext context = createContext(spanType, tracer, traceId, spanId);
    DDSpan span = DDSpan.create("test", 0, context, null);
    CaptureBuffer capture = new CaptureBuffer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, capture));
    packer.format(Collections.singletonList(span), new TraceMapperV0_4());
    packer.flush();
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes));
    int traceCount = capture.messageCount;
    int spanCount = unpacker.unpackArrayHeader();
    int size = unpacker.unpackMapHeader();

    assertEquals(1, traceCount);
    assertEquals(1, spanCount);
    assertEquals(12, size);
    for (int i = 0; i < size; i++) {
      String key = unpacker.unpackString();
      switch (key) {
        case "trace_id":
          MessageFormat traceIdFormat = unpacker.getNextFormat();
          assertEquals(ValueType.INTEGER, traceIdFormat.getValueType());
          if (traceIdFormat == MessageFormat.UINT64) {
            assertEquals(traceId, DDTraceId.from(unpacker.unpackBigInteger().toString()));
          } else {
            assertEquals(traceId, DDTraceId.from(unpacker.unpackLong()));
          }
          break;
        case "span_id":
          MessageFormat spanIdFormat = unpacker.getNextFormat();
          assertEquals(ValueType.INTEGER, spanIdFormat.getValueType());
          if (spanIdFormat == MessageFormat.UINT64) {
            assertEquals(spanId, DDSpanId.from(unpacker.unpackBigInteger().toString()));
          } else {
            assertEquals(spanId, unpacker.unpackLong());
          }
          break;
        default:
          unpacker.unpackValue();
      }
    }
    tracer.close();
  }

  @TableTest({
    "scenario                   | value                | spanType ",
    "zero                       | 0                    |          ",
    "one with type              | 1                    | some-type",
    "max long minus one billion | 8223372036854775807  |          ",
    "Long.MAX_VALUE minus one   | 9223372036854775806  | some-type",
    "Long.MAX_VALUE plus one    | 9223372036854775808  |          ",
    "2^64 minus one             | 18446744073709551615 | some-type"
  })
  void serializeTraceWithIdAsIntV05(String scenario, String value, String spanType)
      throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    DDTraceId traceId = DDTraceId.from(value);
    long spanId = DDSpanId.from(value);
    DDSpanContext context = createContext(spanType, tracer, traceId, spanId);
    DDSpan span = DDSpan.create("test", 0, context, null);
    CaptureBuffer capture = new CaptureBuffer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, capture));
    TraceMapperV0_5 traceMapper = new TraceMapperV0_5();
    packer.format(Collections.singletonList(span), traceMapper);
    packer.flush();
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes));
    int traceCount = capture.messageCount;
    int spanCount = unpacker.unpackArrayHeader();
    int size = unpacker.unpackArrayHeader();

    assertEquals(1, traceCount);
    assertEquals(1, spanCount);
    assertEquals(12, size);
    for (int i = 0; i < size; i++) {
      switch (i) {
        case 3:
          MessageFormat traceIdFormat = unpacker.getNextFormat();
          assertEquals(ValueType.INTEGER, traceIdFormat.getValueType());
          if (traceIdFormat == MessageFormat.UINT64) {
            assertEquals(traceId, DDTraceId.from(unpacker.unpackBigInteger().toString()));
          } else {
            assertEquals(traceId, DDTraceId.from(unpacker.unpackLong()));
          }
          break;
        case 4:
          MessageFormat spanIdFormat = unpacker.getNextFormat();
          assertEquals(ValueType.INTEGER, spanIdFormat.getValueType());
          if (spanIdFormat == MessageFormat.UINT64) {
            assertEquals(spanId, DDSpanId.from(unpacker.unpackBigInteger().toString()));
          } else {
            assertEquals(spanId, unpacker.unpackLong());
          }
          break;
        default:
          unpacker.unpackValue();
      }
    }
    tracer.close();
  }

  @TableTest({
    "scenario                              | baggage     | tags        | expected               | injectBaggage",
    "empty baggage and tags inject         | [:]         | [:]         | [:]                    | true         ",
    "baggage only inject                   | [foo: bbar] | [:]         | [foo: bbar]            | true         ",
    "baggage and tags inject no overlap    | [foo: bbar] | [bar: tfoo] | [foo: bbar, bar: tfoo] | true         ",
    "baggage and tags inject tag wins      | [foo: bbar] | [foo: tbar] | [foo: tbar]            | true         ",
    "empty baggage and tags no inject      | [:]         | [:]         | [:]                    | false        ",
    "baggage only no inject                | [foo: bbar] | [:]         | [:]                    | false        ",
    "baggage and tags no inject no overlap | [foo: bbar] | [bar: tfoo] | [bar: tfoo]            | false        ",
    "baggage and tags no inject tag wins   | [foo: bbar] | [foo: tbar] | [foo: tbar]            | false        "
  })
  void serializeTraceWithBaggageAndTagsCorrectlyV04(
      String scenario,
      Map<String, String> baggage,
      Map<String, String> tags,
      Map<String, String> expected,
      boolean injectBaggage)
      throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    DDSpanContext context =
        new DDSpanContext(
            DDTraceId.ONE,
            1,
            DDSpanId.ZERO,
            null,
            "fakeService",
            "fakeOperation",
            "fakeResource",
            PrioritySampling.UNSET,
            null,
            baggage,
            false,
            null,
            tags.size(),
            tracer.createTraceCollector(DDTraceId.ONE),
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            null,
            injectBaggage,
            true);
    context.setAllTags(tags);
    DDSpan span = DDSpan.create("test", 0, context, null);
    CaptureBuffer capture = new CaptureBuffer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, capture));
    packer.format(Collections.singletonList(span), new TraceMapperV0_4());
    packer.flush();
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes));
    int traceCount = capture.messageCount;
    int spanCount = unpacker.unpackArrayHeader();
    int size = unpacker.unpackMapHeader();

    assertEquals(1, traceCount);
    assertEquals(1, spanCount);
    assertEquals(12, size);
    for (int i = 0; i < size; i++) {
      String key = unpacker.unpackString();
      if ("meta".equals(key)) {
        int packedSize = unpacker.unpackMapHeader();
        Map<String, String> unpackedMeta = new HashMap<>();
        for (int j = 0; j < packedSize; j++) {
          String k = unpacker.unpackString();
          String v = unpacker.unpackString();
          if (!"thread.name".equals(k) && !"thread.id".equals(k)) {
            unpackedMeta.put(k, v);
          }
        }
        assertEquals(expected, unpackedMeta);
      } else {
        unpacker.unpackValue();
      }
    }
    tracer.close();
  }

  @TableTest({
    "scenario                              | baggage     | tags        | expected               | injectBaggage",
    "empty baggage and tags inject         | [:]         | [:]         | [:]                    | true         ",
    "baggage only inject                   | [foo: bbar] | [:]         | [foo: bbar]            | true         ",
    "baggage and tags inject no overlap    | [foo: bbar] | [bar: tfoo] | [foo: bbar, bar: tfoo] | true         ",
    "baggage and tags inject tag wins      | [foo: bbar] | [foo: tbar] | [foo: tbar]            | true         ",
    "empty baggage and tags no inject      | [:]         | [:]         | [:]                    | false        ",
    "baggage only no inject                | [foo: bbar] | [:]         | [:]                    | false        ",
    "baggage and tags no inject no overlap | [foo: bbar] | [bar: tfoo] | [bar: tfoo]            | false        ",
    "baggage and tags no inject tag wins   | [foo: bbar] | [foo: tbar] | [foo: tbar]            | false        "
  })
  void serializeTraceWithBaggageAndTagsCorrectlyV05(
      String scenario,
      Map<String, String> baggage,
      Map<String, String> tags,
      Map<String, String> expected,
      boolean injectBaggage)
      throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    DDSpanContext context =
        new DDSpanContext(
            DDTraceId.ONE,
            1,
            DDSpanId.ZERO,
            null,
            "fakeService",
            "fakeOperation",
            "fakeResource",
            PrioritySampling.UNSET,
            null,
            baggage,
            false,
            null,
            tags.size(),
            tracer.createTraceCollector(DDTraceId.ONE),
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            null,
            injectBaggage,
            true);
    context.setAllTags(tags);
    DDSpan span = DDSpan.create("test", 0, context, null);
    CaptureBuffer capture = new CaptureBuffer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, capture));
    TraceMapperV0_5 mapper = new TraceMapperV0_5();
    packer.format(Collections.singletonList(span), mapper);
    packer.flush();
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes));
    int traceCount = capture.messageCount;
    int spanCount = unpacker.unpackArrayHeader();
    int size = unpacker.unpackArrayHeader();
    String[] dictionary = buildDictionary(mapper);

    assertEquals(1, traceCount);
    assertEquals(1, spanCount);
    assertEquals(12, size);
    for (int i = 0; i < 9; ++i) {
      unpacker.skipValue();
    }
    int packedSize = unpacker.unpackMapHeader();
    Map<String, String> unpackedMeta = new HashMap<>();
    for (int j = 0; j < packedSize; j++) {
      String k = dictionary[unpacker.unpackInt()];
      String v = dictionary[unpacker.unpackInt()];
      if (!"thread.name".equals(k) && !"thread.id".equals(k)) {
        unpackedMeta.put(k, v);
      }
    }
    assertEquals(expected, unpackedMeta);
    tracer.close();
  }

  @Test
  void serializeTraceWithFlatMapTagV04() throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    DDSpanContext context =
        new DDSpanContext(
            DDTraceId.ONE,
            1,
            DDSpanId.ZERO,
            null,
            "fakeService",
            "fakeOperation",
            "fakeResource",
            PrioritySampling.UNSET,
            null,
            null,
            false,
            null,
            0,
            tracer.createTraceCollector(DDTraceId.ONE),
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            null);
    context.setTag("key1", "value1");
    Map<String, String> nested = new HashMap<>();
    nested.put("sub1", "v1");
    nested.put("sub2", "v2");
    context.setTag("key2", nested);
    DDSpan span = DDSpan.create("test", 0, context, null);

    CaptureBuffer capture = new CaptureBuffer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, capture));
    packer.format(Collections.singletonList(span), new TraceMapperV0_4());
    packer.flush();
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes));
    int traceCount = capture.messageCount;
    int spanCount = unpacker.unpackArrayHeader();
    int size = unpacker.unpackMapHeader();

    Map<String, String> expectedMeta = new HashMap<>();
    expectedMeta.put("key1", "value1");
    expectedMeta.put("key2.sub1", "v1");
    expectedMeta.put("key2.sub2", "v2");

    assertEquals(1, traceCount);
    assertEquals(1, spanCount);
    for (int i = 0; i < size; i++) {
      String key = unpacker.unpackString();
      if ("meta".equals(key)) {
        int packedSize = unpacker.unpackMapHeader();
        Map<String, String> unpackedMeta = new HashMap<>();
        for (int j = 0; j < packedSize; j++) {
          String k = unpacker.unpackString();
          String v = unpacker.unpackString();
          if (!"thread.name".equals(k) && !"thread.id".equals(k)) {
            unpackedMeta.put(k, v);
          }
        }
        assertEquals(expectedMeta, unpackedMeta);
      } else {
        unpacker.unpackValue();
      }
    }
    tracer.close();
  }

  @Test
  void serializeTraceWithFlatMapTagV05() throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    DDSpanContext context =
        new DDSpanContext(
            DDTraceId.ONE,
            1,
            DDSpanId.ZERO,
            null,
            "fakeService",
            "fakeOperation",
            "fakeResource",
            PrioritySampling.UNSET,
            null,
            null,
            false,
            null,
            0,
            tracer.createTraceCollector(DDTraceId.ONE),
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            null);
    context.setTag("key1", "value1");
    Map<String, String> nested = new HashMap<>();
    nested.put("sub1", "v1");
    nested.put("sub2", "v2");
    context.setTag("key2", nested);
    DDSpan span = DDSpan.create("test", 0, context, null);

    CaptureBuffer capture = new CaptureBuffer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, capture));
    TraceMapperV0_5 mapper = new TraceMapperV0_5();
    packer.format(Collections.singletonList(span), mapper);
    packer.flush();
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes));
    int traceCount = capture.messageCount;
    int spanCount = unpacker.unpackArrayHeader();
    int size = unpacker.unpackArrayHeader();
    String[] dictionary = buildDictionary(mapper);

    Map<String, String> expectedMeta = new HashMap<>();
    expectedMeta.put("key1", "value1");
    expectedMeta.put("key2.sub1", "v1");
    expectedMeta.put("key2.sub2", "v2");

    assertEquals(1, traceCount);
    assertEquals(1, spanCount);
    assertEquals(12, size);
    for (int i = 0; i < 9; ++i) {
      unpacker.skipValue();
    }
    int packedSize = unpacker.unpackMapHeader();
    Map<String, String> unpackedMeta = new HashMap<>();
    for (int j = 0; j < packedSize; j++) {
      String k = dictionary[unpacker.unpackInt()];
      String v = dictionary[unpacker.unpackInt()];
      if (!"thread.name".equals(k) && !"thread.id".equals(k)) {
        unpackedMeta.put(k, v);
      }
    }
    assertEquals(expectedMeta, unpackedMeta);
    tracer.close();
  }

  private static class CaptureBuffer implements ByteBufferConsumer {
    private byte[] bytes;
    int messageCount;

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      this.messageCount = messageCount;
      this.bytes = new byte[buffer.limit() - buffer.position()];
      buffer.get(bytes);
    }
  }

  private DDSpanContext createContext(
      String spanType, CoreTracer tracer, DDTraceId traceId, long spanId) {
    Map<String, String> baggage = new HashMap<>();
    baggage.put("a-baggage", "value");
    DDSpanContext ctx =
        new DDSpanContext(
            traceId,
            spanId,
            DDSpanId.ZERO,
            null,
            "fakeService",
            "fakeOperation",
            "fakeResource",
            PrioritySampling.UNSET,
            null,
            baggage,
            false,
            spanType,
            1,
            tracer.createTraceCollector(DDTraceId.ONE),
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            null);
    Map<String, Object> tags = new HashMap<>();
    tags.put("k1", "v1");
    ctx.setAllTags(tags);
    return ctx;
  }

  private static String[] buildDictionary(TraceMapperV0_5 mapper) throws Exception {
    GrowableBuffer dictionaryBuffer = TraceMapperTestBridge.getDictionary(mapper);
    Map<?, ?> encoding = TraceMapperTestBridge.getEncoding(mapper);

    String[] dictionary = new String[encoding.size()];
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(dictionaryBuffer.slice());
    for (int i = 0; i < dictionary.length; ++i) {
      dictionary[i] = unpacker.unpackString();
    }
    return dictionary;
  }
}
