package datadog.trace.core;

import static datadog.trace.api.DDTags.SPAN_LINKS;
import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_BAGGAGE_TAG_KEYS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.ddagent.TraceMapperTestBridge;
import datadog.trace.common.writer.ddagent.TraceMapperV0_4;
import datadog.trace.common.writer.ddagent.TraceMapperV0_5;
import datadog.trace.common.writer.ddagent.TraceMapperV1;
import datadog.trace.common.writer.ddagent.V1PayloadReader;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
    DDSpanContext context = createSpanContext(spanType, tracer, traceId, spanId);
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
    DDSpanContext context = createSpanContext(spanType, tracer, traceId, spanId);
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

  @TableTest({
    "scenario         | injectBaggage",
    "baggage enabled  | true         ",
    "baggage disabled | false        "
  })
  @WithConfig(key = TRACE_BAGGAGE_TAG_KEYS, value = "user.id,custom")
  void serializeTraceWithConfiguredBaggageAndTagsCorrectlyV1(String scenario, boolean injectBaggage)
      throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    Map<String, String> baggage = new HashMap<>();
    baggage.put("legacy-baggage", "legacy-value");

    Map<String, String> w3cBaggageItems = new HashMap<>();
    w3cBaggageItems.put("user.id", "user-1");
    w3cBaggageItems.put("custom", "custom-value");
    w3cBaggageItems.put("ignored", "ignored-value");

    DDSpanContext context =
        createSpanContext(tracer, baggage, Baggage.create(w3cBaggageItems), injectBaggage, true, 1);
    context.setTag("span-tag", "span-value");
    DDSpan span = DDSpan.create("test", 0, context, null);

    V1PayloadReader.V1Span payload = V1PayloadReader.readFirstSpan(serializeV1Payload(span));
    Map<String, Object> attributes = payload.getAttributes();

    assertEquals("span-value", attributes.get("span-tag"));
    if (injectBaggage) {
      assertEquals("legacy-value", attributes.get("legacy-baggage"));
      assertEquals("user-1", attributes.get("baggage.user.id"));
      assertEquals("custom-value", attributes.get("baggage.custom"));
    } else {
      assertFalse(attributes.containsKey("legacy-baggage"));
      assertFalse(attributes.containsKey("baggage.user.id"));
      assertFalse(attributes.containsKey("baggage.custom"));
    }
    assertFalse(attributes.containsKey("baggage.ignored"));
    tracer.close();
  }

  @Test
  void serializeTraceWithSpanLinksAsStructuredLinksOnlyV1() throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    DDSpanContext context = createSpanContext(tracer, Collections.emptyMap(), null, true, true, 0);
    DDSpan span = DDSpan.create("test", 0, context, null);

    Map<String, String> linkAttributes = new HashMap<>();
    linkAttributes.put("link.source", "unit-test");
    DDSpanLink link =
        new DDSpanLink(
            DDTraceId.fromHex("11223344556677889900aabbccddeeff"),
            DDSpanId.fromHex("123456789abcdef0"),
            (byte) 1,
            "dd=s:1",
            SpanAttributes.fromMap(linkAttributes));
    span.addLink(link);

    V1PayloadReader.V1Span payload = V1PayloadReader.readFirstSpan(serializeV1Payload(span));

    assertFalse(payload.getAttributes().containsKey(SPAN_LINKS));
    assertEquals(1, payload.getLinks().size());
    V1PayloadReader.V1SpanLink actualLink = payload.getLinks().get(0);
    assertArrayEquals(V1PayloadReader.traceIdBytes(link.traceId()), actualLink.getTraceId());
    assertEquals(link.spanId(), actualLink.getSpanId());
    assertEquals(link.traceState(), actualLink.getTraceState());
    assertEquals((long) (link.traceFlags() & 0xFF), actualLink.getTraceFlags());
    assertEquals("unit-test", actualLink.getAttributes().get("link.source"));
    tracer.close();
  }

  @TableTest({
    "protocol | rootFirst",
    "v0.4     | true     ",
    "v0.4     | false    ",
    "v0.5     | true     ",
    "v0.5     | false    ",
    "v1       | true     ",
    "v1       | false    "
  })
  void serializePropagationTagsOnFirstSpanOfChunk(String protocol, boolean rootFirst)
      throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    PropagationTags propagationTags =
        tracer
            .getPropagationTagsFactory()
            .fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.dm=-3,_dd.p.any=value");
    ExtractedContext extracted =
        new ExtractedContext(
            DDTraceId.ONE, 42, PrioritySampling.SAMPLER_KEEP, null, propagationTags, DATADOG);
    DDSpan root = (DDSpan) tracer.buildSpan("test", "root").asChildOf(extracted).start();
    DDSpan child = (DDSpan) tracer.buildSpan("test", "child").asChildOf(root).start();
    DDSpan grandchild = (DDSpan) tracer.buildSpan("test", "grandchild").asChildOf(child).start();
    root.setTag("root.only", "root-value");

    List<DDSpan> chunk = rootFirst ? Arrays.asList(root, child) : Arrays.asList(child, grandchild);
    List<Map<String, ?>> metadata;
    int samplingMechanism = SamplingMechanism.DEFAULT;
    switch (protocol) {
      case "v0.4":
        metadata = serializeV04Metadata(chunk);
        break;
      case "v0.5":
        metadata = serializeV05Metadata(chunk);
        break;
      case "v1":
        V1PayloadReader.V1Chunk v1Chunk = V1PayloadReader.readFirstChunk(serializeV1Payload(chunk));
        metadata = new ArrayList<>(v1Chunk.getSpans().size());
        for (V1PayloadReader.V1Span span : v1Chunk.getSpans()) {
          metadata.add(span.getAttributes());
        }
        samplingMechanism = v1Chunk.getSamplingMechanism();
        break;
      default:
        throw new IllegalArgumentException("Unexpected protocol: " + protocol);
    }

    assertEquals("-3", metadata.get(0).get("_dd.p.dm"));
    assertEquals("value", metadata.get(0).get("_dd.p.any"));
    assertFalse(metadata.get(1).containsKey("_dd.p.dm"));
    assertFalse(metadata.get(1).containsKey("_dd.p.any"));
    assertEquals(1L, metadata.stream().filter(span -> span.containsKey("_dd.p.dm")).count());
    assertEquals(1L, metadata.stream().filter(span -> span.containsKey("_dd.p.any")).count());
    assertEquals(rootFirst, metadata.get(0).containsKey("root.only"));
    assertFalse(metadata.get(1).containsKey("root.only"));
    if ("v1".equals(protocol)) {
      assertEquals(3, samplingMechanism);
    }
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

  private static List<Map<String, ?>> serializeV04Metadata(List<DDSpan> spans) throws Exception {
    CaptureBuffer capture = new CaptureBuffer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, capture));
    packer.format(spans, new TraceMapperV0_4());
    packer.flush();

    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes));
    int spanCount = unpacker.unpackArrayHeader();
    List<Map<String, ?>> metadata = new ArrayList<>(spanCount);
    for (int i = 0; i < spanCount; i++) {
      int fieldCount = unpacker.unpackMapHeader();
      Map<String, String> spanMetadata = Collections.emptyMap();
      for (int j = 0; j < fieldCount; j++) {
        String field = unpacker.unpackString();
        if (!"meta".equals(field)) {
          unpacker.skipValue();
          continue;
        }
        int metadataSize = unpacker.unpackMapHeader();
        spanMetadata = new HashMap<>(metadataSize);
        for (int k = 0; k < metadataSize; k++) {
          spanMetadata.put(unpacker.unpackString(), unpacker.unpackString());
        }
      }
      metadata.add(spanMetadata);
    }
    return metadata;
  }

  private static List<Map<String, ?>> serializeV05Metadata(List<DDSpan> spans) throws Exception {
    CaptureBuffer capture = new CaptureBuffer();
    TraceMapperV0_5 mapper = new TraceMapperV0_5();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, capture));
    packer.format(spans, mapper);
    packer.flush();

    String[] dictionary = buildDictionary(mapper);
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes));
    int spanCount = unpacker.unpackArrayHeader();
    List<Map<String, ?>> metadata = new ArrayList<>(spanCount);
    for (int i = 0; i < spanCount; i++) {
      assertEquals(12, unpacker.unpackArrayHeader());
      for (int j = 0; j < 9; j++) {
        unpacker.skipValue();
      }
      int metadataSize = unpacker.unpackMapHeader();
      Map<String, String> spanMetadata = new HashMap<>(metadataSize);
      for (int j = 0; j < metadataSize; j++) {
        spanMetadata.put(dictionary[unpacker.unpackInt()], dictionary[unpacker.unpackInt()]);
      }
      metadata.add(spanMetadata);
      unpacker.skipValue();
      unpacker.skipValue();
    }
    return metadata;
  }

  private DDSpanContext createSpanContext(
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

  private DDSpanContext createSpanContext(
      CoreTracer tracer,
      Map<String, String> baggage,
      Baggage w3cBaggage,
      boolean injectBaggage,
      boolean injectLinks,
      int tagsSize) {
    return new DDSpanContext(
        DDTraceId.ONE,
        1,
        DDSpanId.ZERO,
        null,
        null,
        "fakeService",
        "fakeOperation",
        "fakeResource",
        PrioritySampling.UNSET,
        null,
        baggage,
        w3cBaggage,
        false,
        "fakeType",
        tagsSize,
        tracer.createTraceCollector(DDTraceId.ONE),
        null,
        null,
        null,
        NoopPathwayContext.INSTANCE,
        false,
        null,
        ProfilingContextIntegration.NoOp.INSTANCE,
        injectBaggage,
        injectLinks);
  }

  private static byte[] serializeV1Payload(DDSpan span) throws Exception {
    return serializeV1Payload(Collections.singletonList(span));
  }

  private static byte[] serializeV1Payload(List<DDSpan> spans) throws Exception {
    TraceMapperV1 mapper = new TraceMapperV1();
    CapturePayloadBuffer capture = new CapturePayloadBuffer(mapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, capture));
    packer.format(spans, mapper);
    packer.flush();
    assertNotNull(capture.bytes);
    return capture.bytes;
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

  private static final class CapturePayloadBuffer implements ByteBufferConsumer {
    private final TraceMapperV1 mapper;
    private byte[] bytes;

    private CapturePayloadBuffer(TraceMapperV1 mapper) {
      this.mapper = mapper;
    }

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try {
        Payload payload = mapper.newPayload().withBody(messageCount, buffer);
        payload.writeTo(Channels.newChannel(out));
        bytes = out.toByteArray();
      } catch (IOException e) {
        throw new IllegalStateException(e);
      } finally {
        mapper.reset();
      }
    }
  }
}
