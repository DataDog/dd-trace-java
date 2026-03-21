package datadog.trace.core;

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.ddagent.TraceMapperV0_4;
import datadog.trace.common.writer.ddagent.TraceMapperV0_5;
import datadog.trace.core.test.DDCoreSpecification;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.buffer.ArrayBufferInput;
import org.msgpack.value.ValueType;

public class DDSpanSerializationTest extends DDCoreSpecification {

  @BeforeAll
  static void setupSpec() {
    // Disable process tags since they will generate noise on the meta
    System.setProperty("dd." + EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false");
    ProcessTags.reset(datadog.trace.api.Config.get());
  }

  @BeforeEach
  void resetProcessTags() {
    ProcessTags.reset(datadog.trace.api.Config.get());
  }

  @AfterAll
  static void cleanupSpec() {
    System.setProperty("dd." + EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "true");
    ProcessTags.reset(datadog.trace.api.Config.get());
  }

  @ParameterizedTest
  @MethodSource("serializeTraceWithIdAsIntArguments")
  void serializeTraceWithIdAsInt(String value, String spanType) throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      DDTraceId traceId = DDTraceId.from(value);
      long spanId = DDSpanId.from(value);
      DDSpanContext context = createContext(spanType, tracer, traceId, spanId);
      DDSpan span = DDSpan.create("test", 0, context, null);
      CaptureBuffer capture = new CaptureBuffer();
      MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, capture));
      packer.format(Collections.singletonList(span), new TraceMapperV0_4());
      packer.flush();
      org.msgpack.core.MessageUnpacker unpacker =
          MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes));
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
            MessageFormat next = unpacker.getNextFormat();
            assertEquals(ValueType.INTEGER, next.getValueType());
            if (next == MessageFormat.UINT64) {
              assertEquals(traceId, DDTraceId.from(unpacker.unpackBigInteger().toString()));
            } else {
              assertEquals(traceId, DDTraceId.from(unpacker.unpackLong()));
            }
            break;
          case "span_id":
            MessageFormat nextFmt = unpacker.getNextFormat();
            assertEquals(ValueType.INTEGER, nextFmt.getValueType());
            if (nextFmt == MessageFormat.UINT64) {
              assertEquals(spanId, DDSpanId.from(unpacker.unpackBigInteger().toString()));
            } else {
              assertEquals(spanId, unpacker.unpackLong());
            }
            break;
          default:
            unpacker.unpackValue();
        }
      }
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> serializeTraceWithIdAsIntArguments() {
    return Stream.of(
        Arguments.of("0", null),
        Arguments.of("1", "some-type"),
        Arguments.of("8223372036854775807", null),
        Arguments.of(
            BigInteger.valueOf(Long.MAX_VALUE).subtract(BigInteger.ONE).toString(), "some-type"),
        Arguments.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE).toString(), null),
        Arguments.of(
            BigInteger.valueOf(2).pow(64).subtract(BigInteger.ONE).toString(), "some-type"));
  }

  @ParameterizedTest
  @MethodSource("serializeTraceWithIdAsIntV05Arguments")
  void serializeTraceWithIdAsIntV05(String value, String spanType) throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      DDTraceId traceId = DDTraceId.from(value);
      long spanId = DDSpanId.from(value);
      DDSpanContext context = createContext(spanType, tracer, traceId, spanId);
      DDSpan span = DDSpan.create("test", 0, context, null);
      CaptureBuffer capture = new CaptureBuffer();
      MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, capture));
      TraceMapperV0_5 traceMapper = new TraceMapperV0_5();
      packer.format(Collections.singletonList(span), traceMapper);
      packer.flush();
      org.msgpack.core.MessageUnpacker dictionaryUnpacker =
          MessagePack.newDefaultUnpacker(traceMapper.getDictionary());
      String[] dictionary = new String[traceMapper.getEncodingSize()];
      for (int i = 0; i < dictionary.length; ++i) {
        dictionary[i] = dictionaryUnpacker.unpackString();
      }
      org.msgpack.core.MessageUnpacker unpacker =
          MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes));
      int traceCount = capture.messageCount;

      int spanCount = unpacker.unpackArrayHeader();
      int size = unpacker.unpackArrayHeader();

      assertEquals(1, traceCount);
      assertEquals(1, spanCount);
      assertEquals(12, size);
      for (int i = 0; i < size; i++) {
        switch (i) {
          case 3:
            MessageFormat next = unpacker.getNextFormat();
            assertEquals(ValueType.INTEGER, next.getValueType());
            if (next == MessageFormat.UINT64) {
              assertEquals(traceId, DDTraceId.from(unpacker.unpackBigInteger().toString()));
            } else {
              assertEquals(traceId, DDTraceId.from(unpacker.unpackLong()));
            }
            break;
          case 4:
            MessageFormat nextFmt = unpacker.getNextFormat();
            assertEquals(ValueType.INTEGER, nextFmt.getValueType());
            if (nextFmt == MessageFormat.UINT64) {
              assertEquals(spanId, DDSpanId.from(unpacker.unpackBigInteger().toString()));
            } else {
              assertEquals(spanId, unpacker.unpackLong());
            }
            break;
          default:
            unpacker.unpackValue();
        }
      }
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> serializeTraceWithIdAsIntV05Arguments() {
    return Stream.of(
        Arguments.of("0", null),
        Arguments.of("1", "some-type"),
        Arguments.of("8223372036854775807", null),
        Arguments.of(
            BigInteger.valueOf(Long.MAX_VALUE).subtract(BigInteger.ONE).toString(), "some-type"),
        Arguments.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE).toString(), null),
        Arguments.of(
            BigInteger.valueOf(2).pow(64).subtract(BigInteger.ONE).toString(), "some-type"));
  }

  @ParameterizedTest
  @MethodSource("serializeTraceWithBaggageAndTagsV04Arguments")
  void serializeTraceWithBaggageAndTagsCorrectlyV04(
      Map<String, String> baggage,
      Map<String, String> tags,
      Map<String, String> expected,
      boolean injectBaggage)
      throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
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
              injectBaggage);
      context.setAllTags(tags);
      DDSpan span = DDSpan.create("test", 0, context, null);
      CaptureBuffer capture = new CaptureBuffer();
      MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, capture));
      packer.format(Collections.singletonList(span), new TraceMapperV0_4());
      packer.flush();
      org.msgpack.core.MessageUnpacker unpacker =
          MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes));
      int traceCount = capture.messageCount;
      int spanCount = unpacker.unpackArrayHeader();
      int size = unpacker.unpackMapHeader();

      assertEquals(1, traceCount);
      assertEquals(1, spanCount);
      assertEquals(12, size);
      for (int i = 0; i < size; i++) {
        String key = unpacker.unpackString();
        switch (key) {
          case "meta":
            int packedSize = unpacker.unpackMapHeader();
            Map<String, String> unpackedMeta = new HashMap<>();
            for (int j = 0; j < packedSize; j++) {
              String k = unpacker.unpackString();
              String v = unpacker.unpackString();
              if (!k.equals("thread.name") && !k.equals("thread.id")) {
                unpackedMeta.put(k, v);
              }
            }
            assertEquals(expected, unpackedMeta);
            break;
          default:
            unpacker.unpackValue();
        }
      }
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> serializeTraceWithBaggageAndTagsV04Arguments() {
    return Stream.of(
        Arguments.of(emptyMap(), emptyMap(), emptyMap(), true),
        Arguments.of(mapOf("foo", "bbar"), emptyMap(), mapOf("foo", "bbar"), true),
        Arguments.of(
            mapOf("foo", "bbar"), mapOf("bar", "tfoo"), mapOf("foo", "bbar", "bar", "tfoo"), true),
        Arguments.of(mapOf("foo", "bbar"), mapOf("foo", "tbar"), mapOf("foo", "tbar"), true),
        Arguments.of(emptyMap(), emptyMap(), emptyMap(), false),
        Arguments.of(mapOf("foo", "bbar"), emptyMap(), emptyMap(), false),
        Arguments.of(mapOf("foo", "bbar"), mapOf("bar", "tfoo"), mapOf("bar", "tfoo"), false),
        Arguments.of(mapOf("foo", "bbar"), mapOf("foo", "tbar"), mapOf("foo", "tbar"), false));
  }

  @ParameterizedTest
  @MethodSource("serializeTraceWithBaggageAndTagsV05Arguments")
  void serializeTraceWithBaggageAndTagsCorrectlyV05(
      Map<String, String> baggage,
      Map<String, String> tags,
      Map<String, String> expected,
      boolean injectBaggage)
      throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
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
              injectBaggage);
      context.setAllTags(tags);
      DDSpan span = DDSpan.create("test", 0, context, null);
      CaptureBuffer capture = new CaptureBuffer();
      MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, capture));
      TraceMapperV0_5 mapper = new TraceMapperV0_5();
      packer.format(Collections.singletonList(span), mapper);
      packer.flush();
      org.msgpack.core.MessageUnpacker unpacker =
          MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes));
      int traceCount = capture.messageCount;
      int spanCount = unpacker.unpackArrayHeader();
      int size = unpacker.unpackArrayHeader();
      org.msgpack.core.MessageUnpacker dictionaryUnpacker =
          MessagePack.newDefaultUnpacker(mapper.getDictionary());
      String[] dictionary = new String[mapper.getEncodingSize()];
      for (int i = 0; i < dictionary.length; ++i) {
        dictionary[i] = dictionaryUnpacker.unpackString();
      }

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
        if (!k.equals("thread.name") && !k.equals("thread.id")) {
          unpackedMeta.put(k, v);
        }
      }
      assertEquals(expected, unpackedMeta);
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> serializeTraceWithBaggageAndTagsV05Arguments() {
    return Stream.of(
        Arguments.of(emptyMap(), emptyMap(), emptyMap(), true),
        Arguments.of(mapOf("foo", "bbar"), emptyMap(), mapOf("foo", "bbar"), true),
        Arguments.of(
            mapOf("foo", "bbar"), mapOf("bar", "tfoo"), mapOf("foo", "bbar", "bar", "tfoo"), true),
        Arguments.of(mapOf("foo", "bbar"), mapOf("foo", "tbar"), mapOf("foo", "tbar"), true),
        Arguments.of(emptyMap(), emptyMap(), emptyMap(), false),
        Arguments.of(mapOf("foo", "bbar"), emptyMap(), emptyMap(), false),
        Arguments.of(mapOf("foo", "bbar"), mapOf("bar", "tfoo"), mapOf("bar", "tfoo"), false),
        Arguments.of(mapOf("foo", "bbar"), mapOf("foo", "tbar"), mapOf("foo", "tbar"), false));
  }

  @Test
  void serializeTraceWithFlatMapTagV04() throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
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
      Map<String, String> nestedMap = new HashMap<>();
      nestedMap.put("sub1", "v1");
      nestedMap.put("sub2", "v2");
      context.setTag("key2", nestedMap);
      DDSpan span = DDSpan.create("test", 0, context, null);

      CaptureBuffer capture = new CaptureBuffer();
      MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, capture));
      packer.format(Collections.singletonList(span), new TraceMapperV0_4());
      packer.flush();
      org.msgpack.core.MessageUnpacker unpacker =
          MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes));
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
        switch (key) {
          case "meta":
            int packedSize = unpacker.unpackMapHeader();
            Map<String, String> unpackedMeta = new HashMap<>();
            for (int j = 0; j < packedSize; j++) {
              String k = unpacker.unpackString();
              String v = unpacker.unpackString();
              if (!k.equals("thread.name") && !k.equals("thread.id")) {
                unpackedMeta.put(k, v);
              }
            }
            assertEquals(expectedMeta, unpackedMeta);
            break;
          default:
            unpacker.unpackValue();
        }
      }
    } finally {
      tracer.close();
    }
  }

  @Test
  void serializeTraceWithFlatMapTagV05() throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
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
      Map<String, String> nestedMap = new HashMap<>();
      nestedMap.put("sub1", "v1");
      nestedMap.put("sub2", "v2");
      context.setTag("key2", nestedMap);
      DDSpan span = DDSpan.create("test", 0, context, null);

      CaptureBuffer capture = new CaptureBuffer();
      MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, capture));
      TraceMapperV0_5 mapper = new TraceMapperV0_5();
      packer.format(Collections.singletonList(span), mapper);
      packer.flush();
      org.msgpack.core.MessageUnpacker unpacker =
          MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes));
      int traceCount = capture.messageCount;
      int spanCount = unpacker.unpackArrayHeader();
      int size = unpacker.unpackArrayHeader();
      org.msgpack.core.MessageUnpacker dictionaryUnpacker =
          MessagePack.newDefaultUnpacker(mapper.getDictionary());
      String[] dictionary = new String[mapper.getEncodingSize()];
      for (int i = 0; i < dictionary.length; ++i) {
        dictionary[i] = dictionaryUnpacker.unpackString();
      }

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
        if (!k.equals("thread.name") && !k.equals("thread.id")) {
          unpackedMeta.put(k, v);
        }
      }
      assertEquals(expectedMeta, unpackedMeta);
    } finally {
      tracer.close();
    }
  }

  private static class CaptureBuffer implements ByteBufferConsumer {
    byte[] bytes;
    int messageCount;

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      this.messageCount = messageCount;
      this.bytes = new byte[buffer.limit() - buffer.position()];
      buffer.get(bytes);
    }
  }

  DDSpanContext createContext(String spanType, CoreTracer tracer, DDTraceId traceId, long spanId) {
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
            Collections.singletonMap("a-baggage", "value"),
            false,
            spanType,
            1,
            tracer.createTraceCollector(DDTraceId.ONE),
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            null);
    ctx.setAllTags(Collections.singletonMap("k1", "v1"));
    return ctx;
  }

  private static Map<String, String> emptyMap() {
    return new HashMap<>();
  }

  private static Map<String, String> mapOf(String... pairs) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(pairs[i], pairs[i + 1]);
    }
    return map;
  }
}
