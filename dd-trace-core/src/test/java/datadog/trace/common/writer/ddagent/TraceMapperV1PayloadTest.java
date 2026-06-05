package datadog.trace.common.writer.ddagent;

import static datadog.trace.common.writer.TraceGenerator.generateRandomTraces;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.msgpack.core.MessageFormat.FIXSTR;
import static org.msgpack.core.MessageFormat.STR16;
import static org.msgpack.core.MessageFormat.STR32;
import static org.msgpack.core.MessageFormat.STR8;

import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.TraceGenerator;
import datadog.trace.core.MetadataConsumer;
import datadog.trace.junit.utils.tabletest.SamplingMechanismConverter;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.tabletest.junit.TableTest;

class TraceMapperV1PayloadTest extends DDJavaSpecification {

  @TableTest({
    "scenario             | bufferSize | traceCount | lowCardinality",
    "0 traces low card    | 20480      | 0          | true          ",
    "1 trace low card     | 20480      | 1          | true          ",
    "2 traces low card    | 30720      | 2          | true          ",
    "0 traces high card   | 20480      | 0          | false         ",
    "1 trace high card    | 20480      | 1          | false         ",
    "2 traces high card   | 30720      | 2          | false         ",
    "10 traces low card   | 102400     | 10         | true          ",
    "100 traces high card | 102400     | 100        | false         "
  })
  void testTracesWrittenCorrectly(int bufferSize, int traceCount, boolean lowCardinality) {
    List<List<TraceGenerator.PojoSpan>> traces = generateRandomTraces(traceCount, lowCardinality);
    TraceMapperV1 traceMapper = new TraceMapperV1();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(bufferSize, verifier));

    boolean tracesFitInBuffer = true;
    for (List<TraceGenerator.PojoSpan> trace : traces) {
      if (!packer.format(trace, traceMapper)) {
        verifier.skipLargeTrace();
        tracesFitInBuffer = false;
        traceMapper.reset();
      }
    }
    packer.flush();

    if (tracesFitInBuffer) {
      verifier.verifyTracesConsumed();
    }
  }

  @Test
  void testEndpointReturnsV10() {
    assertEquals("v1.0", new TraceMapperV1().endpoint());
  }

  @Test
  void testSpanKindValueConversion() {
    assertEquals(TraceMapperV1.SPAN_KIND_UNSPECIFIED, TraceMapperV1.getSpanKindValue(null));
    assertEquals(
        TraceMapperV1.SPAN_KIND_INTERNAL, TraceMapperV1.getSpanKindValue(Tags.SPAN_KIND_INTERNAL));
    assertEquals(
        TraceMapperV1.SPAN_KIND_SERVER, TraceMapperV1.getSpanKindValue(Tags.SPAN_KIND_SERVER));
    assertEquals(
        TraceMapperV1.SPAN_KIND_CLIENT, TraceMapperV1.getSpanKindValue(Tags.SPAN_KIND_CLIENT));
    assertEquals(
        TraceMapperV1.SPAN_KIND_PRODUCER, TraceMapperV1.getSpanKindValue(Tags.SPAN_KIND_PRODUCER));
    assertEquals(
        TraceMapperV1.SPAN_KIND_CONSUMER, TraceMapperV1.getSpanKindValue(Tags.SPAN_KIND_CONSUMER));
    assertEquals(TraceMapperV1.SPAN_KIND_INTERNAL, TraceMapperV1.getSpanKindValue("unknown"));
  }

  @Test
  void testPayloadContainsExpectedHeaderAndChunkFields() throws IOException {
    Map<String, Object> tags = new HashMap<>();
    tags.put(Tags.ENV, "prod");
    tags.put(Tags.VERSION, "1.2.3");
    tags.put(Tags.COMPONENT, "http-client");
    tags.put(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    tags.put("attr.string", "value");
    tags.put("attr.bool", true);
    tags.put("attr.number", 12.5d);
    tags.put("_dd.p.dm", "-3");

    TraceGenerator.PojoSpan span =
        new TraceGenerator.PojoSpan(
            "service-a",
            "operation-a",
            "resource-a",
            DDTraceId.ONE,
            123L,
            0L,
            1000L,
            2000L,
            1,
            Collections.emptyMap(),
            tags,
            "web",
            false,
            PrioritySampling.SAMPLER_KEEP,
            200,
            "rum");

    TraceMapperV1 mapper = new TraceMapperV1();
    byte[] encoded =
        serializeMappedPayload(mapper, Collections.singletonList(Collections.singletonList(span)));
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded);
    List<String> stringTable = new ArrayList<>();
    stringTable.add("");

    int payloadFieldCount = unpacker.unpackMapHeader();
    Set<Integer> payloadFieldsSeen = new HashSet<>();
    int chunkCount = -1;
    Map<String, Object> payloadAttributes = null;

    for (int i = 0; i < payloadFieldCount; i++) {
      int fieldId = unpacker.unpackInt();
      payloadFieldsSeen.add(fieldId);
      switch (fieldId) {
        case 2:
        case 3:
        case 4:
        case 5:
        case 6:
        case 7:
        case 8:
        case 9:
          readStreamingString(unpacker, stringTable);
          break;
        case 10:
          payloadAttributes = readAttributes(unpacker, stringTable);
          break;
        case 11:
          chunkCount = unpacker.unpackArrayHeader();
          assertEquals(1, chunkCount);
          verifyChunk(unpacker, Collections.singletonList(span), stringTable);
          break;
        default:
          Assertions.fail("Unexpected payload field id: " + fieldId);
      }
    }

    assertEquals(10, payloadFieldCount);
    Set<Integer> expectedFields = new HashSet<>();
    for (int i = 2; i <= 11; i++) {
      expectedFields.add(i);
    }
    assertEquals(expectedFields, payloadFieldsSeen);
    assertEquals(1, chunkCount);
    assertNotNull(payloadAttributes);
    if (ProcessTags.getTagsForSerialization() == null) {
      assertEquals(0, payloadAttributes.size());
    } else {
      assertEquals(1, payloadAttributes.size());
      assertEquals(
          ProcessTags.getTagsForSerialization().toString(),
          payloadAttributes.get(DDTags.PROCESS_TAGS));
    }
  }

  @TableTest({
    "scenario        | decisionMakerTag | expectedSamplingMechanism",
    "null tag        |                  | SamplingMechanism.DEFAULT",
    "simple negative | '-3'             | 3                        ",
    "compound        | '934086a686-7'   | 7                        ",
    "invalid         | 'invalid'        | SamplingMechanism.DEFAULT"
  })
  void testSamplingMechanismNormalizationFromDdPDm(
      String decisionMakerTag,
      @ConvertWith(SamplingMechanismConverter.class) int expectedSamplingMechanism)
      throws IOException {
    Map<String, Object> dmTags =
        decisionMakerTag == null
            ? Collections.emptyMap()
            : Collections.singletonMap("_dd.p.dm", decisionMakerTag);

    TraceGenerator.PojoSpan span =
        new TraceGenerator.PojoSpan(
            "service-a",
            "operation-a",
            "resource-a",
            DDTraceId.ONE,
            321L,
            0L,
            1000L,
            2000L,
            0,
            Collections.emptyMap(),
            dmTags,
            "custom",
            false,
            PrioritySampling.SAMPLER_KEEP,
            200,
            null);

    TraceMapperV1 mapper = new TraceMapperV1();
    byte[] encoded =
        serializeMappedPayload(mapper, Collections.singletonList(Collections.singletonList(span)));
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded);
    List<String> stringTable = new ArrayList<>();
    stringTable.add("");

    unpacker.unpackMapHeader();
    int samplingMechanism = -1;

    for (int i = 0; i < 10; i++) {
      int payloadFieldId = unpacker.unpackInt();
      if (payloadFieldId == 11) {
        int chunkCount = unpacker.unpackArrayHeader();
        assertEquals(1, chunkCount);
        int chunkFieldCount = unpacker.unpackMapHeader();
        for (int j = 0; j < chunkFieldCount; j++) {
          int chunkFieldId = unpacker.unpackInt();
          if (chunkFieldId == 7) {
            samplingMechanism = unpacker.unpackInt();
          } else {
            skipChunkField(unpacker, chunkFieldId, stringTable);
          }
        }
      } else {
        skipPayloadField(unpacker, payloadFieldId, stringTable);
      }
    }

    assertEquals(expectedSamplingMechanism, samplingMechanism);
  }

  @Test
  void testSpanIdsAreEncodedAsUnsignedValuesInV1Payloads() throws IOException {
    long spanId = Long.MIN_VALUE + 123L;
    long parentId = Long.MIN_VALUE + 456L;
    TraceGenerator.PojoSpan span =
        new TraceGenerator.PojoSpan(
            "service-a",
            "operation-a",
            "resource-a",
            DDTraceId.ONE,
            spanId,
            parentId,
            1000L,
            2000L,
            0,
            Collections.emptyMap(),
            Collections.emptyMap(),
            "web",
            false,
            PrioritySampling.SAMPLER_KEEP,
            200,
            null);

    TraceMapperV1 mapper = new TraceMapperV1();
    byte[] encoded =
        serializeMappedPayload(mapper, Collections.singletonList(Collections.singletonList(span)));
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded);
    List<String> stringTable = new ArrayList<>();
    stringTable.add("");

    unpacker.unpackMapHeader();
    Long actualSpanId = null;
    Long actualParentId = null;

    for (int i = 0; i < 10; i++) {
      int payloadFieldId = unpacker.unpackInt();
      if (payloadFieldId == 11) {
        int chunkCount = unpacker.unpackArrayHeader();
        assertEquals(1, chunkCount);
        int chunkFieldCount = unpacker.unpackMapHeader();
        for (int j = 0; j < chunkFieldCount; j++) {
          int chunkFieldId = unpacker.unpackInt();
          if (chunkFieldId == 4) {
            int spanCount = unpacker.unpackArrayHeader();
            assertEquals(1, spanCount);
            int spanFieldCount = unpacker.unpackMapHeader();
            for (int k = 0; k < spanFieldCount; k++) {
              int spanFieldId = unpacker.unpackInt();
              switch (spanFieldId) {
                case 4:
                  assertEquals(MessageFormat.UINT64, unpacker.getNextFormat());
                  actualSpanId = DDSpanId.from(unpacker.unpackBigInteger().toString());
                  break;
                case 5:
                  assertEquals(MessageFormat.UINT64, unpacker.getNextFormat());
                  actualParentId = DDSpanId.from(unpacker.unpackBigInteger().toString());
                  break;
                default:
                  skipSpanField(unpacker, spanFieldId, stringTable);
              }
            }
          } else {
            skipChunkField(unpacker, chunkFieldId, stringTable);
          }
        }
      } else {
        skipPayloadField(unpacker, payloadFieldId, stringTable);
      }
    }

    assertEquals(spanId, actualSpanId);
    assertEquals(parentId, actualParentId);
  }

  @Test
  void testSpanLinksAreEncodedFromStructuredSpanLinks() throws IOException {
    Map<String, String> linkAttrs = new HashMap<>();
    linkAttrs.put("link.kind", "follows_from");
    linkAttrs.put("context_headers", "tracecontext");

    List<AgentSpanLink> spanLinks =
        Arrays.asList(
            new TestSpanLink(
                DDTraceId.fromHex("11223344556677889900aabbccddeeff"),
                DDSpanId.fromHex("000000000000002a"),
                (byte) 1,
                "dd=s:1",
                SpanAttributes.fromMap(linkAttrs)),
            new TestSpanLink(
                DDTraceId.fromHex("00000000000000000000000000000001"),
                DDSpanId.fromHex("0000000000000002"),
                (byte) 0,
                "",
                SpanAttributes.EMPTY));

    TraceGenerator.PojoSpan span =
        new TraceGenerator.PojoSpan(
            "service-a",
            "operation-a",
            "resource-a",
            DDTraceId.ONE,
            123L,
            0L,
            1000L,
            2000L,
            0,
            Collections.emptyMap(),
            Collections.emptyMap(),
            "web",
            false,
            PrioritySampling.SAMPLER_KEEP,
            200,
            null,
            spanLinks);

    TraceMapperV1 mapper = new TraceMapperV1();
    byte[] encoded =
        serializeMappedPayload(mapper, Collections.singletonList(Collections.singletonList(span)));
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded);
    List<String> stringTable = new ArrayList<>();
    stringTable.add("");

    List<Map<String, Object>> links = readFirstSpanLinks(unpacker, stringTable);

    assertEquals(2, links.size());
    assertArrayEquals(
        traceIdBytes(DDTraceId.fromHex("11223344556677889900aabbccddeeff")),
        (byte[]) links.get(0).get("traceId"));
    assertEquals(DDSpanId.fromHex("000000000000002a"), links.get(0).get("spanId"));
    assertEquals("dd=s:1", links.get(0).get("tracestate"));
    assertEquals(1L, links.get(0).get("flags"));
    Map<String, Object> expectedLinkAttrs0 = new HashMap<>();
    expectedLinkAttrs0.put("link.kind", "follows_from");
    expectedLinkAttrs0.put("context_headers", "tracecontext");
    assertEquals(expectedLinkAttrs0, links.get(0).get("attributes"));

    assertArrayEquals(
        traceIdBytes(DDTraceId.fromHex("00000000000000000000000000000001")),
        (byte[]) links.get(1).get("traceId"));
    assertEquals(DDSpanId.fromHex("0000000000000002"), links.get(1).get("spanId"));
    assertEquals("", links.get(1).get("tracestate"));
    assertEquals(0L, links.get(1).get("flags"));
    assertEquals(Collections.emptyMap(), links.get(1).get("attributes"));
  }

  @Test
  void testFirstSpanTagsAreProcessedOnce() {
    CountingPojoSpan firstSpan =
        new CountingPojoSpan(
            "service-a",
            "operation-a",
            "resource-a",
            DDTraceId.ONE,
            123L,
            0L,
            1000L,
            2000L,
            0,
            Collections.emptyMap(),
            Collections.singletonMap(Tags.HTTP_URL, "http://localhost:7777/"),
            "web",
            false,
            PrioritySampling.SAMPLER_KEEP,
            200,
            null);

    CountingPojoSpan secondSpan =
        new CountingPojoSpan(
            "service-a",
            "operation-b",
            "resource-b",
            DDTraceId.ONE,
            456L,
            123L,
            1000L,
            2000L,
            0,
            Collections.emptyMap(),
            Collections.singletonMap(Tags.HTTP_URL, "http://localhost:7777/"),
            "web",
            false,
            PrioritySampling.SAMPLER_KEEP,
            200,
            null);

    TraceMapperV1 mapper = new TraceMapperV1();

    serializeMappedPayload(mapper, Collections.singletonList(Arrays.asList(firstSpan, secondSpan)));

    assertEquals(1, firstSpan.processTagsAndBaggageCount);
    assertEquals(1, secondSpan.processTagsAndBaggageCount);
  }

  @Test
  void testMissingSpanLinksEncodeEmptyLinks() throws IOException {
    TraceGenerator.PojoSpan span =
        new TraceGenerator.PojoSpan(
            "service-a",
            "operation-a",
            "resource-a",
            DDTraceId.ONE,
            123L,
            0L,
            1000L,
            2000L,
            0,
            Collections.emptyMap(),
            Collections.emptyMap(),
            "web",
            false,
            PrioritySampling.SAMPLER_KEEP,
            200,
            null);

    TraceMapperV1 mapper = new TraceMapperV1();
    byte[] encoded =
        serializeMappedPayload(mapper, Collections.singletonList(Collections.singletonList(span)));
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded);
    List<String> stringTable = new ArrayList<>();
    stringTable.add("");

    List<Map<String, Object>> links = readFirstSpanLinks(unpacker, stringTable);

    assertTrue(links.isEmpty());
  }

  @Test
  void testSpanEventsAreEncodedFromEventsTag() throws IOException {
    Map<String, Object> eventOneAttrs = new HashMap<>();
    eventOneAttrs.put("str", "v");
    eventOneAttrs.put("int", 42L);
    eventOneAttrs.put("double", 12.5d);
    eventOneAttrs.put("bool", true);
    eventOneAttrs.put("arr", Arrays.asList("x", 7L, 2.5d, false));

    Map<String, Object> eventOne = new HashMap<>();
    eventOne.put("time_unix_nano", 1234567890L);
    eventOne.put("name", "event.one");
    eventOne.put("attributes", eventOneAttrs);

    Map<String, Object> eventTwo = new HashMap<>();
    eventTwo.put("time_unix_nano", 1234567891L);
    eventTwo.put("name", "event.two");

    Map<String, Object> tags =
        Collections.singletonMap("events", Arrays.asList(eventOne, eventTwo));

    TraceGenerator.PojoSpan span =
        new TraceGenerator.PojoSpan(
            "service-a",
            "operation-a",
            "resource-a",
            DDTraceId.ONE,
            123L,
            0L,
            1000L,
            2000L,
            0,
            Collections.emptyMap(),
            tags,
            "web",
            false,
            PrioritySampling.SAMPLER_KEEP,
            200,
            null);

    TraceMapperV1 mapper = new TraceMapperV1();
    byte[] encoded =
        serializeMappedPayload(mapper, Collections.singletonList(Collections.singletonList(span)));
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded);
    List<String> stringTable = new ArrayList<>();
    stringTable.add("");

    List<Map<String, Object>> events = readFirstSpanEvents(unpacker, stringTable);

    assertEquals(2, events.size());
    assertEquals(1234567890L, events.get(0).get("timeUnixNano"));
    assertEquals("event.one", events.get(0).get("name"));
    Map<String, Object> decodedAttrs0 = (Map<String, Object>) events.get(0).get("attributes");
    assertEquals("v", decodedAttrs0.get("str"));
    assertEquals(42L, decodedAttrs0.get("int"));
    assertEquals(12.5d, ((Number) decodedAttrs0.get("double")).doubleValue(), 0.000001d);
    assertEquals(true, decodedAttrs0.get("bool"));
    assertEquals(Arrays.<Object>asList("x", 7L, 2.5d, false), decodedAttrs0.get("arr"));

    assertEquals(1234567891L, events.get(1).get("timeUnixNano"));
    assertEquals("event.two", events.get(1).get("name"));
    assertEquals(Collections.emptyMap(), events.get(1).get("attributes"));
  }

  @Test
  void testMalformedSpanEventsFallBackToEmptyEvents() throws IOException {
    Map<String, Object> malformedEvent = Collections.singletonMap("foo", "bar");
    Map<String, Object> tags = Collections.singletonMap("events", malformedEvent);

    TraceGenerator.PojoSpan span =
        new TraceGenerator.PojoSpan(
            "service-a",
            "operation-a",
            "resource-a",
            DDTraceId.ONE,
            123L,
            0L,
            1000L,
            2000L,
            0,
            Collections.emptyMap(),
            tags,
            "web",
            false,
            PrioritySampling.SAMPLER_KEEP,
            200,
            null);

    TraceMapperV1 mapper = new TraceMapperV1();
    byte[] encoded =
        serializeMappedPayload(mapper, Collections.singletonList(Collections.singletonList(span)));
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded);
    List<String> stringTable = new ArrayList<>();
    stringTable.add("");

    List<Map<String, Object>> events = readFirstSpanEvents(unpacker, stringTable);

    assertTrue(events.isEmpty());
  }

  @Test
  void testMetaStructIsEncodedAsBytesAttribute() throws IOException {
    TraceGenerator.PojoSpan span =
        new TraceGenerator.PojoSpan(
            "service-a",
            "operation-a",
            "resource-a",
            DDTraceId.ONE,
            123L,
            0L,
            1000L,
            2000L,
            0,
            Collections.emptyMap(),
            Collections.emptyMap(),
            "web",
            false,
            PrioritySampling.SAMPLER_KEEP,
            200,
            null);

    Map<String, Object> metaStructValue = new HashMap<>();
    metaStructValue.put("foo", "bar");
    metaStructValue.put("answer", 42L);
    span.setMetaStruct("meta_key", metaStructValue);

    TraceMapperV1 mapper = new TraceMapperV1();
    byte[] encoded =
        serializeMappedPayload(mapper, Collections.singletonList(Collections.singletonList(span)));
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded);
    List<String> stringTable = new ArrayList<>();
    stringTable.add("");

    Map<String, Object> attributes = readFirstSpanAttributes(unpacker, stringTable);
    byte[] metaStructBytes = (byte[]) attributes.get("meta_key");
    MessageUnpacker metaStructUnpacker = MessagePack.newDefaultUnpacker(metaStructBytes);
    int metaStructFieldCount = metaStructUnpacker.unpackMapHeader();
    Map<String, Object> decodedMetaStruct = new HashMap<>();
    for (int i = 0; i < metaStructFieldCount; i++) {
      String key = metaStructUnpacker.unpackString();
      switch (metaStructUnpacker.getNextFormat().getValueType()) {
        case INTEGER:
          decodedMetaStruct.put(key, metaStructUnpacker.unpackLong());
          break;
        case STRING:
          decodedMetaStruct.put(key, metaStructUnpacker.unpackString());
          break;
        default:
          Assertions.fail("Unexpected meta_struct value type for key " + key);
      }
    }

    assertNotNull(metaStructBytes);
    assertEquals("bar", decodedMetaStruct.get("foo"));
    assertEquals(42L, decodedMetaStruct.get("answer"));
  }

  @Test
  void testMapValuedSpanTagsAreFlattenedInV1Attributes() throws IOException {
    Map<String, Object> profile = new HashMap<>();
    profile.put("age", 30L);

    Map<String, Object> usr = new HashMap<>();
    usr.put("id", "123");
    usr.put("name", "alice");
    usr.put("authenticated", true);
    usr.put("profile", profile);

    Map<String, Object> metadata0 = new HashMap<>();
    metadata0.put("event", "login");
    metadata0.put("attempts", 1L);

    Map<String, Object> metadata1 = new HashMap<>();
    metadata1.put("blocked", false);

    Map<String, Object> appsecEvents = new HashMap<>();
    appsecEvents.put("metadata0", metadata0);
    appsecEvents.put("metadata1", metadata1);

    Map<String, Object> tags = new HashMap<>();
    tags.put("usr", usr);
    tags.put("appsec.events.users.login.success", appsecEvents);

    TraceGenerator.PojoSpan span =
        new TraceGenerator.PojoSpan(
            "service-a",
            "operation-a",
            "resource-a",
            DDTraceId.ONE,
            123L,
            0L,
            1000L,
            2000L,
            0,
            Collections.emptyMap(),
            tags,
            "web",
            false,
            PrioritySampling.SAMPLER_KEEP,
            0,
            null);

    TraceMapperV1 mapper = new TraceMapperV1();
    byte[] encoded =
        serializeMappedPayload(mapper, Collections.singletonList(Collections.singletonList(span)));
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded);
    List<String> stringTable = new ArrayList<>();
    stringTable.add("");

    Map<String, Object> attributes = readFirstSpanAttributes(unpacker, stringTable);

    assertTrue(attributes.containsKey("usr.id"));
    assertTrue(attributes.containsKey("usr.name"));
    assertTrue(attributes.containsKey("usr.authenticated"));
    assertTrue(attributes.containsKey("usr.profile.age"));
    assertTrue(attributes.containsKey("appsec.events.users.login.success.metadata0.event"));
    assertTrue(attributes.containsKey("appsec.events.users.login.success.metadata0.attempts"));
    assertTrue(attributes.containsKey("appsec.events.users.login.success.metadata1.blocked"));

    assertEquals("123", attributes.get("usr.id"));
    assertEquals("alice", attributes.get("usr.name"));
    assertEquals(true, attributes.get("usr.authenticated"));
    assertEquals(30d, ((Number) attributes.get("usr.profile.age")).doubleValue(), 0.000001d);
    assertEquals("login", attributes.get("appsec.events.users.login.success.metadata0.event"));
    assertEquals(
        1d,
        ((Number) attributes.get("appsec.events.users.login.success.metadata0.attempts"))
            .doubleValue(),
        0.000001d);
    assertEquals(false, attributes.get("appsec.events.users.login.success.metadata1.blocked"));

    assertFalse(attributes.containsKey("usr"));
    assertFalse(attributes.containsKey("appsec.events.users.login.success"));
  }

  @Test
  void testPrimitiveSpanTagsAreEncodedInV1Attributes() throws IOException {
    Map<String, Object> tags = new HashMap<>();
    tags.put("tag.bool", true);
    tags.put("tag.int", 7);
    tags.put("tag.long", 9L);
    tags.put("tag.float", 3.5f);
    tags.put("tag.double", 4.25d);

    TraceGenerator.PojoSpan span =
        new TraceGenerator.PojoSpan(
            "service-a",
            "operation-a",
            "resource-a",
            DDTraceId.ONE,
            123L,
            0L,
            1000L,
            2000L,
            0,
            Collections.emptyMap(),
            tags,
            "web",
            false,
            PrioritySampling.SAMPLER_KEEP,
            0,
            null);

    TraceMapperV1 mapper = new TraceMapperV1();
    byte[] encoded =
        serializeMappedPayload(mapper, Collections.singletonList(Collections.singletonList(span)));
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded);
    List<String> stringTable = new ArrayList<>();
    stringTable.add("");

    Map<String, Object> attributes = readFirstSpanAttributes(unpacker, stringTable);

    assertEquals(true, attributes.get("tag.bool"));
    assertEquals(7d, ((Number) attributes.get("tag.int")).doubleValue(), 0.000001d);
    assertEquals(9d, ((Number) attributes.get("tag.long")).doubleValue(), 0.000001d);
    assertEquals(3.5d, ((Number) attributes.get("tag.float")).doubleValue(), 0.000001d);
    assertEquals(4.25d, ((Number) attributes.get("tag.double")).doubleValue(), 0.000001d);
  }

  @Test
  void testThreadMetadataIsEncodedInV1Attributes() throws IOException {
    TraceGenerator.PojoSpan span =
        new TraceGenerator.PojoSpan(
            "service-a",
            "operation-a",
            "resource-a",
            DDTraceId.ONE,
            123L,
            0L,
            1000L,
            2000L,
            0,
            Collections.emptyMap(),
            Collections.emptyMap(),
            "web",
            false,
            PrioritySampling.SAMPLER_KEEP,
            0,
            null);

    TraceMapperV1 mapper = new TraceMapperV1();
    byte[] encoded =
        serializeMappedPayload(mapper, Collections.singletonList(Collections.singletonList(span)));
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded);
    List<String> stringTable = new ArrayList<>();
    stringTable.add("");

    Map<String, Object> attributes = readFirstSpanAttributes(unpacker, stringTable);

    assertAttributeValueEquals(
        span.getTag(DDTags.THREAD_ID), attributes.get(DDTags.THREAD_ID), DDTags.THREAD_ID);
    assertEquals(span.getTag(DDTags.THREAD_NAME).toString(), attributes.get(DDTags.THREAD_NAME));
  }

  // --- Inner classes ---

  private static final class PayloadVerifier implements ByteBufferConsumer, WritableByteChannel {

    private final List<List<TraceGenerator.PojoSpan>> expectedTraces;
    private final TraceMapperV1 mapper;
    private ByteBuffer captured = ByteBuffer.allocate(200 << 10);
    private int position = 0;

    private PayloadVerifier(
        List<List<TraceGenerator.PojoSpan>> expectedTraces, TraceMapperV1 mapper) {
      this.expectedTraces = expectedTraces;
      this.mapper = mapper;
    }

    void skipLargeTrace() {
      ++position;
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

        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(captured);
        if (messageCount == 0) {
          assertEquals(0, unpacker.unpackMapHeader());
          return;
        }

        List<String> stringTable = new ArrayList<>();
        stringTable.add("");

        int payloadFieldCount = unpacker.unpackMapHeader();
        assertEquals(10, payloadFieldCount);

        boolean seenChunks = false;
        for (int i = 0; i < payloadFieldCount; i++) {
          int fieldId = unpacker.unpackInt();
          if (fieldId == 11) {
            int traceCount = unpacker.unpackArrayHeader();
            assertEquals(messageCount, traceCount);
            seenChunks = true;
            for (int traceIndex = 0; traceIndex < traceCount; traceIndex++) {
              List<TraceGenerator.PojoSpan> expectedTrace = expectedTraces.get(position++);
              verifyChunk(unpacker, expectedTrace, stringTable);
            }
          } else {
            skipPayloadField(unpacker, fieldId, stringTable);
          }
        }

        assertTrue(seenChunks);
      } catch (IOException e) {
        Assertions.fail(e.getMessage());
      } finally {
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

  private static class CountingPojoSpan extends TraceGenerator.PojoSpan {
    int processTagsAndBaggageCount = 0;

    CountingPojoSpan(
        String serviceName,
        String operationName,
        CharSequence resourceName,
        DDTraceId traceId,
        long spanId,
        long parentId,
        long start,
        long duration,
        int error,
        Map<String, String> baggage,
        Map<String, Object> tags,
        CharSequence type,
        boolean measured,
        int samplingPriority,
        int statusCode,
        CharSequence origin) {
      super(
          serviceName,
          operationName,
          resourceName,
          traceId,
          spanId,
          parentId,
          start,
          duration,
          error,
          baggage,
          tags,
          type,
          measured,
          samplingPriority,
          statusCode,
          origin);
    }

    @Override
    public void processTagsAndBaggage(MetadataConsumer consumer) {
      processTagsAndBaggageCount++;
      super.processTagsAndBaggage(consumer);
    }

    @Override
    public void processTagsAndBaggage(
        MetadataConsumer consumer, boolean injectLinksAsTags, boolean injectBaggageAsTags) {
      processTagsAndBaggageCount++;
      super.processTagsAndBaggage(consumer, injectLinksAsTags, injectBaggageAsTags);
    }
  }

  private static class ByteArrayChannel implements WritableByteChannel {
    private byte[] data = new byte[0];

    @Override
    public int write(ByteBuffer src) {
      int len = src.remaining();
      byte[] incoming = new byte[len];
      src.get(incoming);
      byte[] combined = new byte[data.length + incoming.length];
      System.arraycopy(data, 0, combined, 0, data.length);
      System.arraycopy(incoming, 0, combined, data.length, incoming.length);
      data = combined;
      return len;
    }

    byte[] bytes() {
      return data;
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void close() {}
  }

  private static class CapturedBody implements ByteBufferConsumer {
    private final TraceMapperV1 mapper;
    private byte[] payloadBytes;

    private CapturedBody(TraceMapperV1 mapper) {
      this.mapper = mapper;
    }

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      Payload payload = mapper.newPayload().withBody(messageCount, buffer);
      payloadBytes = serializePayload(payload);
      mapper.reset();
    }
  }

  /** Subclass to expose SpanLink's protected constructor for testing. */
  private static class TestSpanLink extends SpanLink {
    TestSpanLink(
        DDTraceId traceId,
        long spanId,
        byte traceFlags,
        String traceState,
        SpanAttributes attributes) {
      super(traceId, spanId, traceFlags, traceState, attributes);
    }
  }

  // --- Helper methods ---

  private static void verifyChunk(
      MessageUnpacker unpacker,
      List<TraceGenerator.PojoSpan> expectedTrace,
      List<String> stringTable)
      throws IOException {
    int chunkFieldCount = unpacker.unpackMapHeader();
    assertEquals(6, chunkFieldCount);

    Integer priority = null;
    String origin = null;
    Map<String, Object> chunkAttributes = null;
    byte[] traceId = null;
    Integer samplingMechanism = null;
    List<TraceGenerator.PojoSpan> decodedSpans = null;

    for (int i = 0; i < chunkFieldCount; i++) {
      int fieldId = unpacker.unpackInt();
      switch (fieldId) {
        case 1:
          priority = unpacker.unpackInt();
          break;
        case 2:
          origin = readStreamingString(unpacker, stringTable);
          break;
        case 3:
          chunkAttributes = readAttributes(unpacker, stringTable);
          break;
        case 4:
          decodedSpans = verifySpans(unpacker, expectedTrace, stringTable);
          break;
        case 6:
          int traceIdLen = unpacker.unpackBinaryHeader();
          traceId = new byte[traceIdLen];
          unpacker.readPayload(traceId);
          break;
        case 7:
          samplingMechanism = unpacker.unpackInt();
          break;
        default:
          Assertions.fail("Unexpected chunk field id: " + fieldId);
      }
    }

    assertNotNull(priority);
    assertNotNull(origin);
    assertNotNull(chunkAttributes);
    assertNotNull(decodedSpans);
    assertNotNull(traceId);
    assertNotNull(samplingMechanism);

    TraceGenerator.PojoSpan firstSpan = expectedTrace.get(0);
    assertEquals(firstSpan.samplingPriority(), (int) priority);
    assertEqualsWithNullAsEmpty(firstSpan.getOrigin(), origin);
    assertEquals(1, chunkAttributes.size());
    assertEqualsWithNullAsEmpty(
        firstSpan.getLocalRootSpan().getServiceName(), (String) chunkAttributes.get("service"));
    assertArrayEquals(traceIdBytes(firstSpan.getTraceId()), traceId);
    assertEquals(expectedSamplingMechanism(firstSpan.getTags()), (int) samplingMechanism);
  }

  private static byte[] traceIdBytes(DDTraceId traceId) {
    return ByteBuffer.allocate(16)
        .putLong(traceId.toHighOrderLong())
        .putLong(traceId.toLong())
        .array();
  }

  private static List<TraceGenerator.PojoSpan> verifySpans(
      MessageUnpacker unpacker,
      List<TraceGenerator.PojoSpan> expectedTrace,
      List<String> stringTable)
      throws IOException {
    int spanCount = unpacker.unpackArrayHeader();
    assertEquals(expectedTrace.size(), spanCount);

    for (int i = 0; i < spanCount; i++) {
      verifySpan(unpacker, expectedTrace.get(i), stringTable);
    }
    return expectedTrace;
  }

  private static void verifySpan(
      MessageUnpacker unpacker, TraceGenerator.PojoSpan expectedSpan, List<String> stringTable)
      throws IOException {
    int spanFieldCount = unpacker.unpackMapHeader();
    assertEquals(16, spanFieldCount);

    String service = null;
    String name = null;
    String resource = null;
    Long spanId = null;
    Long parentId = null;
    Long start = null;
    Long duration = null;
    Boolean error = null;
    Map<String, Object> attributes = null;
    String type = null;
    int linksCount = -1;
    int eventsCount = -1;
    String env = null;
    String version = null;
    String component = null;
    Integer spanKind = null;

    for (int i = 0; i < spanFieldCount; i++) {
      int fieldId = unpacker.unpackInt();
      switch (fieldId) {
        case 1:
          service = readStreamingString(unpacker, stringTable);
          break;
        case 2:
          name = readStreamingString(unpacker, stringTable);
          break;
        case 3:
          resource = readStreamingString(unpacker, stringTable);
          break;
        case 4:
          spanId = unpackUnsignedLong(unpacker);
          break;
        case 5:
          parentId = unpackUnsignedLong(unpacker);
          break;
        case 6:
          start = unpacker.unpackLong();
          break;
        case 7:
          duration = unpacker.unpackLong();
          break;
        case 8:
          error = unpacker.unpackBoolean();
          break;
        case 9:
          attributes = readAttributes(unpacker, stringTable);
          break;
        case 10:
          type = readStreamingString(unpacker, stringTable);
          break;
        case 11:
          linksCount = unpacker.unpackArrayHeader();
          break;
        case 12:
          eventsCount = unpacker.unpackArrayHeader();
          break;
        case 13:
          env = readStreamingString(unpacker, stringTable);
          break;
        case 14:
          version = readStreamingString(unpacker, stringTable);
          break;
        case 15:
          component = readStreamingString(unpacker, stringTable);
          break;
        case 16:
          spanKind = unpacker.unpackInt();
          break;
        default:
          Assertions.fail("Unexpected span field id: " + fieldId);
      }
    }

    assertEqualsWithNullAsEmpty(expectedSpan.getServiceName(), service);
    assertEqualsWithNullAsEmpty(expectedSpan.getOperationName(), name);
    assertEqualsWithNullAsEmpty(expectedSpan.getResourceName(), resource);
    assertEquals(expectedSpan.getSpanId(), (long) spanId);
    assertEquals(expectedSpan.getParentId(), (long) parentId);
    assertEquals(expectedSpan.getStartTime(), (long) start);
    assertEquals(expectedSpan.getDurationNano(), (long) duration);
    assertEquals(expectedSpan.getError() != 0, error);
    assertEqualsWithNullAsEmpty(expectedSpan.getType(), type);
    assertEquals(0, linksCount);
    assertEquals(0, eventsCount);
    assertEqualsWithNullAsEmpty(expectedSpan.getTag(Tags.ENV), env);
    assertEqualsWithNullAsEmpty(expectedSpan.getTag(Tags.VERSION), version);
    assertEqualsWithNullAsEmpty(expectedSpan.getTag(Tags.COMPONENT), component);
    assertEquals(
        TraceMapperV1.getSpanKindValue(expectedSpan.getTag(Tags.SPAN_KIND)), (int) spanKind);

    assertNotNull(attributes);
    int expectedHttpStatusCode = expectedSpan.getHttpStatusCode();
    boolean shouldContainHttpStatus =
        expectedHttpStatusCode != 0 && !expectedSpan.getTags().containsKey("http.status_code");
    Map<String, Object> expectedAttributes = new HashMap<>(expectedSpan.getBaggage());
    expectedAttributes.put(DDTags.THREAD_ID, expectedSpan.getTag(DDTags.THREAD_ID));
    expectedAttributes.put(DDTags.THREAD_NAME, expectedSpan.getTag(DDTags.THREAD_NAME));
    for (Map.Entry<String, Object> entry : expectedSpan.getTags().entrySet()) {
      if (DDTags.SPAN_EVENTS.equals(entry.getKey())) {
        continue;
      }
      addFlattenedExpectedAttribute(expectedAttributes, entry.getKey(), entry.getValue());
    }
    if (shouldContainHttpStatus) {
      expectedAttributes.put("http.status_code", Integer.toString(expectedHttpStatusCode));
    }
    if (expectedSpan.isTopLevel()) {
      expectedAttributes.put(InstrumentationTags.DD_TOP_LEVEL.toString(), 1d);
    }

    assertEquals(expectedAttributes.size(), attributes.size());
    for (Map.Entry<String, Object> entry : expectedAttributes.entrySet()) {
      String key = entry.getKey();
      Object expectedValue = entry.getValue();
      assertTrue(attributes.containsKey(key), "Missing attribute key: " + key);
      assertAttributeValueEquals(expectedValue, attributes.get(key), key);
    }
  }

  private static Map<String, Object> readAttributes(
      MessageUnpacker unpacker, List<String> stringTable) throws IOException {
    int attrArraySize = unpacker.unpackArrayHeader();
    assertEquals(0, attrArraySize % 3);
    int attrCount = attrArraySize / 3;

    Map<String, Object> attributes = new HashMap<>();
    for (int i = 0; i < attrCount; i++) {
      String key = readStreamingString(unpacker, stringTable);
      int attrType = unpacker.unpackInt();
      Object value;
      switch (attrType) {
        case TraceMapperV1.VALUE_TYPE_STRING:
          value = readStreamingString(unpacker, stringTable);
          break;
        case TraceMapperV1.VALUE_TYPE_BOOLEAN:
          value = unpacker.unpackBoolean();
          break;
        case TraceMapperV1.VALUE_TYPE_FLOAT:
          value = unpacker.unpackDouble();
          break;
        case TraceMapperV1.VALUE_TYPE_BYTES:
          int len = unpacker.unpackBinaryHeader();
          byte[] data = new byte[len];
          unpacker.readPayload(data);
          value = data;
          break;
        default:
          value = Assertions.fail("Unknown attribute value type: " + attrType);
      }
      attributes.put(key, value);
    }
    return attributes;
  }

  private static void assertAttributeValueEquals(Object expected, Object actual, String key) {
    if (expected instanceof Number) {
      assertInstanceOf(Number.class, actual, "Attribute " + key + " should be numeric");
      double expectedValue = ((Number) expected).doubleValue();
      double actualValue = ((Number) actual).doubleValue();
      double delta = Math.max(0.000001d, Math.abs(expectedValue) * 0.000000000001d);
      assertEquals(expectedValue, actualValue, delta, "Numeric mismatch for " + key);
    } else if (expected instanceof Boolean) {
      assertEquals(expected, actual, "Boolean mismatch for " + key);
    } else {
      assertEquals(String.valueOf(expected), String.valueOf(actual), "String mismatch for " + key);
    }
  }

  private static long unpackUnsignedLong(MessageUnpacker unpacker) throws IOException {
    MessageFormat format = unpacker.getNextFormat();
    if (format == MessageFormat.UINT64) {
      return DDSpanId.from(unpacker.unpackBigInteger().toString());
    }
    return unpacker.unpackLong();
  }

  private static void addFlattenedExpectedAttribute(
      Map<String, Object> expectedAttributes, String key, Object value) {
    if (!(value instanceof Map)) {
      expectedAttributes.put(key, value);
      return;
    }
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
      addFlattenedExpectedAttribute(
          expectedAttributes, key + "." + entry.getKey(), entry.getValue());
    }
  }

  private static int expectedSamplingMechanism(Map<String, Object> tags) {
    Object decisionMakerRaw = tags.get("_dd.p.dm");
    if (decisionMakerRaw == null) {
      return SamplingMechanism.DEFAULT;
    }

    String decisionMaker = String.valueOf(decisionMakerRaw);
    try {
      int value = Integer.parseInt(decisionMaker);
      return value < 0 ? -value : value;
    } catch (NumberFormatException ignored) {
      int separator = decisionMaker.lastIndexOf('-');
      if (separator >= 0 && separator + 1 < decisionMaker.length()) {
        try {
          int value = Integer.parseInt(decisionMaker.substring(separator + 1));
          return value < 0 ? -value : value;
        } catch (NumberFormatException ignoredAgain) {
          // fall through
        }
      }
      return SamplingMechanism.DEFAULT;
    }
  }

  private static String readStreamingString(MessageUnpacker unpacker, List<String> stringTable)
      throws IOException {
    MessageFormat format = unpacker.getNextFormat();
    if (format == FIXSTR || format == STR8 || format == STR16 || format == STR32) {
      String value = unpacker.unpackString();
      if (!stringTable.contains(value)) {
        stringTable.add(value);
      }
      return value;
    }

    int index = unpacker.unpackInt();
    assertTrue(index >= 0 && index < stringTable.size(), "Invalid string-table index: " + index);
    return stringTable.get(index);
  }

  private static void skipPayloadField(
      MessageUnpacker unpacker, int fieldId, List<String> stringTable) throws IOException {
    switch (fieldId) {
      case 2:
      case 3:
      case 4:
      case 5:
      case 6:
      case 7:
      case 8:
      case 9:
        readStreamingString(unpacker, stringTable);
        break;
      case 10:
        readAttributes(unpacker, stringTable);
        break;
      default:
        Assertions.fail("Unexpected payload field id while skipping: " + fieldId);
    }
  }

  private static void skipChunkField(
      MessageUnpacker unpacker, int fieldId, List<String> stringTable) throws IOException {
    switch (fieldId) {
      case 1:
        unpacker.unpackInt();
        break;
      case 2:
        readStreamingString(unpacker, stringTable);
        break;
      case 3:
        readAttributes(unpacker, stringTable);
        break;
      case 4:
        int spanCount = unpacker.unpackArrayHeader();
        for (int i = 0; i < spanCount; i++) {
          skipSpan(unpacker, stringTable);
        }
        break;
      case 5:
        unpacker.unpackBoolean();
        break;
      case 6:
        int len = unpacker.unpackBinaryHeader();
        byte[] ignored = new byte[len];
        unpacker.readPayload(ignored);
        break;
      case 7:
        unpacker.unpackInt();
        break;
      default:
        Assertions.fail("Unexpected chunk field id while skipping: " + fieldId);
    }
  }

  private static void skipSpan(MessageUnpacker unpacker, List<String> stringTable)
      throws IOException {
    int fieldCount = unpacker.unpackMapHeader();
    for (int i = 0; i < fieldCount; i++) {
      int fieldId = unpacker.unpackInt();
      switch (fieldId) {
        case 1:
        case 2:
        case 3:
        case 10:
        case 13:
        case 14:
        case 15:
          readStreamingString(unpacker, stringTable);
          break;
        case 4:
        case 5:
          unpacker.unpackValue().asNumberValue().toLong();
          break;
        case 6:
        case 7:
          unpacker.unpackLong();
          break;
        case 8:
          unpacker.unpackBoolean();
          break;
        case 9:
          int attrArraySize = unpacker.unpackArrayHeader();
          int attrCount = attrArraySize / 3;
          for (int j = 0; j < attrCount; j++) {
            readStreamingString(unpacker, stringTable);
            int type = unpacker.unpackInt();
            switch (type) {
              case TraceMapperV1.VALUE_TYPE_STRING:
                readStreamingString(unpacker, stringTable);
                break;
              case TraceMapperV1.VALUE_TYPE_BOOLEAN:
                unpacker.unpackBoolean();
                break;
              case TraceMapperV1.VALUE_TYPE_FLOAT:
                unpacker.unpackDouble();
                break;
              case TraceMapperV1.VALUE_TYPE_BYTES:
                int blen = unpacker.unpackBinaryHeader();
                byte[] bignored = new byte[blen];
                unpacker.readPayload(bignored);
                break;
              default:
                Assertions.fail("Unexpected attribute type while skipping: " + type);
            }
          }
          break;
        case 11:
        case 12:
          unpacker.unpackArrayHeader();
          break;
        case 16:
          unpacker.unpackInt();
          break;
        default:
          Assertions.fail("Unexpected span field id while skipping: " + fieldId);
      }
    }
  }

  private static Map<String, Object> readFirstSpanAttributes(
      MessageUnpacker unpacker, List<String> stringTable) throws IOException {
    int payloadFieldCount = unpacker.unpackMapHeader();
    for (int i = 0; i < payloadFieldCount; i++) {
      int payloadFieldId = unpacker.unpackInt();
      if (payloadFieldId != 11) {
        skipPayloadField(unpacker, payloadFieldId, stringTable);
        continue;
      }

      int chunkCount = unpacker.unpackArrayHeader();
      assertEquals(1, chunkCount);

      int chunkFieldCount = unpacker.unpackMapHeader();
      for (int chunkFieldIndex = 0; chunkFieldIndex < chunkFieldCount; chunkFieldIndex++) {
        int chunkFieldId = unpacker.unpackInt();
        if (chunkFieldId != 4) {
          skipChunkField(unpacker, chunkFieldId, stringTable);
          continue;
        }

        int spanCount = unpacker.unpackArrayHeader();
        assertEquals(1, spanCount);

        int spanFieldCount = unpacker.unpackMapHeader();
        for (int spanFieldIndex = 0; spanFieldIndex < spanFieldCount; spanFieldIndex++) {
          int spanFieldId = unpacker.unpackInt();
          if (spanFieldId == 9) {
            return readAttributes(unpacker, stringTable);
          }
          skipSpanField(unpacker, spanFieldId, stringTable);
        }
      }
    }
    return Assertions.fail("Could not find span attributes field in first span");
  }

  private static List<Map<String, Object>> readFirstSpanLinks(
      MessageUnpacker unpacker, List<String> stringTable) throws IOException {
    int payloadFieldCount = unpacker.unpackMapHeader();
    for (int i = 0; i < payloadFieldCount; i++) {
      int payloadFieldId = unpacker.unpackInt();
      if (payloadFieldId != 11) {
        skipPayloadField(unpacker, payloadFieldId, stringTable);
        continue;
      }

      int chunkCount = unpacker.unpackArrayHeader();
      assertEquals(1, chunkCount);

      int chunkFieldCount = unpacker.unpackMapHeader();
      for (int chunkFieldIndex = 0; chunkFieldIndex < chunkFieldCount; chunkFieldIndex++) {
        int chunkFieldId = unpacker.unpackInt();
        if (chunkFieldId != 4) {
          skipChunkField(unpacker, chunkFieldId, stringTable);
          continue;
        }

        int spanCount = unpacker.unpackArrayHeader();
        assertEquals(1, spanCount);

        int spanFieldCount = unpacker.unpackMapHeader();
        for (int spanFieldIndex = 0; spanFieldIndex < spanFieldCount; spanFieldIndex++) {
          int spanFieldId = unpacker.unpackInt();
          if (spanFieldId == 11) {
            return readSpanLinks(unpacker, stringTable);
          }
          skipSpanField(unpacker, spanFieldId, stringTable);
        }
      }
    }
    return Assertions.fail("Could not find span links field in first span");
  }

  private static void skipSpanField(MessageUnpacker unpacker, int fieldId, List<String> stringTable)
      throws IOException {
    switch (fieldId) {
      case 1:
      case 2:
      case 3:
      case 10:
      case 13:
      case 14:
      case 15:
        readStreamingString(unpacker, stringTable);
        break;
      case 4:
      case 5:
        unpacker.unpackValue().asNumberValue().toLong();
        break;
      case 6:
      case 7:
        unpacker.unpackLong();
        break;
      case 8:
        unpacker.unpackBoolean();
        break;
      case 9:
        readAttributes(unpacker, stringTable);
        break;
      case 12:
        int eventsCount = unpacker.unpackArrayHeader();
        for (int j = 0; j < eventsCount; j++) {
          skipSpanEvent(unpacker, stringTable);
        }
        break;
      case 11:
        int linksCount = unpacker.unpackArrayHeader();
        for (int j = 0; j < linksCount; j++) {
          int linkFieldCount = unpacker.unpackMapHeader();
          for (int k = 0; k < linkFieldCount; k++) {
            int linkFieldId = unpacker.unpackInt();
            switch (linkFieldId) {
              case 1:
                int traceIdLen = unpacker.unpackBinaryHeader();
                byte[] traceIdIgnored = new byte[traceIdLen];
                unpacker.readPayload(traceIdIgnored);
                break;
              case 2:
              case 5:
                unpacker.unpackValue().asNumberValue().toLong();
                break;
              case 3:
                readAttributes(unpacker, stringTable);
                break;
              case 4:
                readStreamingString(unpacker, stringTable);
                break;
              default:
                Assertions.fail("Unexpected span link field id while skipping: " + linkFieldId);
            }
          }
        }
        break;
      case 16:
        unpacker.unpackInt();
        break;
      default:
        Assertions.fail("Unexpected span field id while skipping: " + fieldId);
    }
  }

  private static List<Map<String, Object>> readSpanLinks(
      MessageUnpacker unpacker, List<String> stringTable) throws IOException {
    int linksCount = unpacker.unpackArrayHeader();
    List<Map<String, Object>> links = new ArrayList<>();

    for (int i = 0; i < linksCount; i++) {
      int linkFieldCount = unpacker.unpackMapHeader();
      assertEquals(5, linkFieldCount);

      byte[] traceId = null;
      Long spanId = null;
      Map<String, Object> attributes = null;
      String tracestate = null;
      Long flags = null;

      for (int j = 0; j < linkFieldCount; j++) {
        int linkFieldId = unpacker.unpackInt();
        switch (linkFieldId) {
          case 1:
            int traceIdLen = unpacker.unpackBinaryHeader();
            traceId = new byte[traceIdLen];
            unpacker.readPayload(traceId);
            break;
          case 2:
            spanId = unpacker.unpackValue().asNumberValue().toLong();
            break;
          case 3:
            attributes = readAttributes(unpacker, stringTable);
            break;
          case 4:
            tracestate = readStreamingString(unpacker, stringTable);
            break;
          case 5:
            flags = unpacker.unpackValue().asNumberValue().toLong();
            break;
          default:
            Assertions.fail("Unexpected span link field id: " + linkFieldId);
        }
      }

      Map<String, Object> link = new HashMap<>();
      link.put("traceId", traceId);
      link.put("spanId", spanId);
      link.put("attributes", attributes);
      link.put("tracestate", tracestate);
      link.put("flags", flags);
      links.add(link);
    }

    return links;
  }

  private static List<Map<String, Object>> readFirstSpanEvents(
      MessageUnpacker unpacker, List<String> stringTable) throws IOException {
    int payloadFieldCount = unpacker.unpackMapHeader();
    for (int i = 0; i < payloadFieldCount; i++) {
      int payloadFieldId = unpacker.unpackInt();
      if (payloadFieldId != 11) {
        skipPayloadField(unpacker, payloadFieldId, stringTable);
        continue;
      }

      int chunkCount = unpacker.unpackArrayHeader();
      assertEquals(1, chunkCount);

      int chunkFieldCount = unpacker.unpackMapHeader();
      for (int chunkFieldIndex = 0; chunkFieldIndex < chunkFieldCount; chunkFieldIndex++) {
        int chunkFieldId = unpacker.unpackInt();
        if (chunkFieldId != 4) {
          skipChunkField(unpacker, chunkFieldId, stringTable);
          continue;
        }

        int spanCount = unpacker.unpackArrayHeader();
        assertEquals(1, spanCount);

        int spanFieldCount = unpacker.unpackMapHeader();
        for (int spanFieldIndex = 0; spanFieldIndex < spanFieldCount; spanFieldIndex++) {
          int spanFieldId = unpacker.unpackInt();
          if (spanFieldId == 12) {
            return readSpanEvents(unpacker, stringTable);
          }
          skipSpanField(unpacker, spanFieldId, stringTable);
        }
      }
    }
    return Assertions.fail("Could not find span events field in first span");
  }

  private static List<Map<String, Object>> readSpanEvents(
      MessageUnpacker unpacker, List<String> stringTable) throws IOException {
    int eventsCount = unpacker.unpackArrayHeader();
    List<Map<String, Object>> events = new ArrayList<>();

    for (int i = 0; i < eventsCount; i++) {
      int eventFieldCount = unpacker.unpackMapHeader();
      assertEquals(3, eventFieldCount);

      Long timeUnixNano = null;
      String name = null;
      Map<String, Object> attributes = null;

      for (int j = 0; j < eventFieldCount; j++) {
        int eventFieldId = unpacker.unpackInt();
        switch (eventFieldId) {
          case 1:
            timeUnixNano = unpacker.unpackLong();
            break;
          case 2:
            name = readStreamingString(unpacker, stringTable);
            break;
          case 3:
            attributes = readEventAttributes(unpacker, stringTable);
            break;
          default:
            Assertions.fail("Unexpected span event field id: " + eventFieldId);
        }
      }

      Map<String, Object> event = new HashMap<>();
      event.put("timeUnixNano", timeUnixNano);
      event.put("name", name);
      event.put("attributes", attributes);
      events.add(event);
    }
    return events;
  }

  private static Map<String, Object> readEventAttributes(
      MessageUnpacker unpacker, List<String> stringTable) throws IOException {
    int attrArraySize = unpacker.unpackArrayHeader();
    assertEquals(0, attrArraySize % 3);
    int attrCount = attrArraySize / 3;
    Map<String, Object> attributes = new HashMap<>();

    for (int i = 0; i < attrCount; i++) {
      String key = readStreamingString(unpacker, stringTable);
      int attrType = unpacker.unpackInt();
      Object value;
      switch (attrType) {
        case TraceMapperV1.VALUE_TYPE_STRING:
          value = readStreamingString(unpacker, stringTable);
          break;
        case TraceMapperV1.VALUE_TYPE_BOOLEAN:
          value = unpacker.unpackBoolean();
          break;
        case TraceMapperV1.VALUE_TYPE_FLOAT:
          value = unpacker.unpackDouble();
          break;
        case TraceMapperV1.VALUE_TYPE_INT:
          value = unpacker.unpackLong();
          break;
        case TraceMapperV1.VALUE_TYPE_ARRAY:
          value = readEventArrayValue(unpacker, stringTable);
          break;
        default:
          value = Assertions.fail("Unknown event attribute value type: " + attrType);
      }
      attributes.put(key, value);
    }
    return attributes;
  }

  private static List<Object> readEventArrayValue(
      MessageUnpacker unpacker, List<String> stringTable) throws IOException {
    int itemArraySize = unpacker.unpackArrayHeader();
    assertEquals(0, itemArraySize % 2);
    int itemCount = itemArraySize / 2;
    List<Object> values = new ArrayList<>();
    for (int i = 0; i < itemCount; i++) {
      int itemType = unpacker.unpackInt();
      switch (itemType) {
        case TraceMapperV1.VALUE_TYPE_STRING:
          values.add(readStreamingString(unpacker, stringTable));
          break;
        case TraceMapperV1.VALUE_TYPE_BOOLEAN:
          values.add(unpacker.unpackBoolean());
          break;
        case TraceMapperV1.VALUE_TYPE_FLOAT:
          values.add(unpacker.unpackDouble());
          break;
        case TraceMapperV1.VALUE_TYPE_INT:
          values.add(unpacker.unpackLong());
          break;
        default:
          Assertions.fail("Unknown event array item type: " + itemType);
      }
    }
    return values;
  }

  private static void skipSpanEvent(MessageUnpacker unpacker, List<String> stringTable)
      throws IOException {
    int fieldCount = unpacker.unpackMapHeader();
    for (int i = 0; i < fieldCount; i++) {
      int fieldId = unpacker.unpackInt();
      switch (fieldId) {
        case 1:
          unpacker.unpackLong();
          break;
        case 2:
          readStreamingString(unpacker, stringTable);
          break;
        case 3:
          readEventAttributes(unpacker, stringTable);
          break;
        default:
          Assertions.fail("Unexpected event field id while skipping: " + fieldId);
      }
    }
  }

  private static byte[] serializeMappedPayload(
      TraceMapperV1 mapper, List<List<TraceGenerator.PojoSpan>> traces) {
    CapturedBody capturedBody = new CapturedBody(mapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(2 << 20, capturedBody));

    for (List<TraceGenerator.PojoSpan> trace : traces) {
      assertTrue(packer.format(trace, mapper));
    }
    packer.flush();

    assertNotNull(capturedBody.payloadBytes);
    return capturedBody.payloadBytes;
  }

  private static byte[] serializePayload(Payload payload) {
    ByteArrayChannel channel = new ByteArrayChannel();
    try {
      payload.writeTo(channel);
    } catch (IOException e) {
      Assertions.fail(e.getMessage());
    }
    return channel.bytes();
  }

  private static void assertEqualsWithNullAsEmpty(CharSequence expected, String actual) {
    if (expected == null) {
      assertEquals("", actual);
    } else {
      assertEquals(expected.toString(), actual);
    }
  }
}
