package datadog.trace.common.writer.ddagent;

import static datadog.trace.api.DDTags.PROCESS_TAGS;
import static datadog.trace.api.DDTags.SPAN_EVENTS;
import static datadog.trace.api.DDTags.THREAD_ID;
import static datadog.trace.api.DDTags.THREAD_NAME;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.ENV;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_STATUS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.VERSION;
import static datadog.trace.common.writer.TraceGenerator.generateRandomTraces;
import static datadog.trace.common.writer.ddagent.PayloadVerifiers.assertEqualsWithNullAsEmpty;
import static datadog.trace.common.writer.ddagent.V1PayloadReader.newStringTable;
import static datadog.trace.common.writer.ddagent.V1PayloadReader.readAttributes;
import static datadog.trace.common.writer.ddagent.V1PayloadReader.readBinary;
import static datadog.trace.common.writer.ddagent.V1PayloadReader.readFirstChunk;
import static datadog.trace.common.writer.ddagent.V1PayloadReader.readFirstSpan;
import static datadog.trace.common.writer.ddagent.V1PayloadReader.readStreamingString;
import static datadog.trace.common.writer.ddagent.V1PayloadReader.skipChunkField;
import static datadog.trace.common.writer.ddagent.V1PayloadReader.skipPayloadField;
import static datadog.trace.common.writer.ddagent.V1PayloadReader.skipSpanField;
import static datadog.trace.common.writer.ddagent.V1PayloadReader.traceIdBytes;
import static datadog.trace.common.writer.ddagent.V1PayloadReader.unpackUnsignedLong;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.TraceGenerator.PojoSpan;
import datadog.trace.common.writer.ddagent.V1PayloadReader.ChunkField;
import datadog.trace.common.writer.ddagent.V1PayloadReader.PayloadField;
import datadog.trace.common.writer.ddagent.V1PayloadReader.SpanField;
import datadog.trace.common.writer.ddagent.V1PayloadReader.V1SpanEvent;
import datadog.trace.common.writer.ddagent.V1PayloadReader.V1SpanLink;
import datadog.trace.core.MetadataConsumer;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueType;
import org.tabletest.junit.TableTest;

@ExtendWith(WithConfigExtension.class)
class TraceMapperV1PayloadTest {

  private static final String DECISION_MAKER_TAG = "_dd.p.dm";

  /** Every field the v1 payload header carries, mirroring {@code TraceMapperV1.buildHeader}. */
  private static final Set<Integer> EXPECTED_PAYLOAD_FIELD_IDS =
      new HashSet<>(
          asList(
              PayloadField.CONTAINER_ID,
              PayloadField.LANGUAGE_NAME,
              PayloadField.LANGUAGE_VERSION,
              PayloadField.TRACER_VERSION,
              PayloadField.RUNTIME_ID,
              PayloadField.ENV,
              PayloadField.HOSTNAME,
              PayloadField.APP_VERSION,
              PayloadField.ATTRIBUTES,
              PayloadField.CHUNKS));

  /** Every field a v1 span carries, mirroring {@code TraceMapperV1.encodeSpans}. */
  private static final Set<Integer> EXPECTED_SPAN_FIELD_IDS =
      new HashSet<>(
          asList(
              SpanField.SERVICE,
              SpanField.NAME,
              SpanField.RESOURCE,
              SpanField.SPAN_ID,
              SpanField.PARENT_ID,
              SpanField.START,
              SpanField.DURATION,
              SpanField.ERROR,
              SpanField.ATTRIBUTES,
              SpanField.TYPE,
              SpanField.LINKS,
              SpanField.EVENTS,
              SpanField.ENV,
              SpanField.VERSION,
              SpanField.COMPONENT,
              SpanField.KIND));

  // Keep the ProcessTags static in sync with the (per-test rebuilt) Config, the way DDSpecification
  // did for the original Spock tests. Runs after WithConfigExtension has rebuilt Config.
  @BeforeEach
  void syncProcessTags() {
    ProcessTags.reset(Config.get());
  }

  @TableTest({
    "scenario                         | bufferSizeKb | traceCount | lowCardinality",
    "no traces, low cardinality       | 20           | 0          | true          ",
    "one trace, low cardinality       | 20           | 1          | true          ",
    "two traces, low cardinality      | 30           | 2          | true          ",
    "no traces, high cardinality      | 20           | 0          | false         ",
    "one trace, high cardinality      | 20           | 1          | false         ",
    "two traces, high cardinality     | 30           | 2          | false         ",
    "ten traces, low cardinality      | 100          | 10         | true          ",
    "hundred traces, high cardinality | 100          | 100        | false         "
  })
  void tracesWrittenCorrectly(
      String scenario, int bufferSizeKb, int traceCount, boolean lowCardinality) {
    List<List<PojoSpan>> traces = generateRandomTraces(traceCount, lowCardinality);
    TraceMapperV1 traceMapper = new TraceMapperV1();
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(bufferSizeKb << 10, verifier));

    boolean tracesFitInBuffer = true;
    for (List<PojoSpan> trace : traces) {
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
  void endpointReturnsV1() {
    assertEquals("v1.0", new TraceMapperV1().endpoint());
  }

  // expectedKind holds the wire values, which must stay in sync with TraceMapperV1.SPAN_KIND_*.
  @TableTest({
    "scenario               | spanKind | expectedKind",
    "no span.kind tag       |          | 0           ",
    "internal span kind     | internal | 1           ",
    "server span kind       | server   | 2           ",
    "client span kind       | client   | 3           ",
    "producer span kind     | producer | 4           ",
    "consumer span kind     | consumer | 5           ",
    "unrecognized span kind | unknown  | 1           "
  })
  void spanKindValueConversion(String scenario, String spanKind, int expectedKind) {
    assertEquals(expectedKind, TraceMapperV1.getSpanKindValue(spanKind));
  }

  @Test
  void payloadContainsExpectedHeaderAndChunkFields() throws IOException {
    Map<String, Object> tags = new HashMap<>();
    tags.put(ENV, "prod");
    tags.put(VERSION, "1.2.3");
    tags.put(COMPONENT, "http-client");
    tags.put(SPAN_KIND, SPAN_KIND_CLIENT);
    tags.put("attr.string", "value");
    tags.put("attr.bool", true);
    tags.put("attr.number", 12.5d);
    PojoSpan span =
        new PojoSpan(
            "service-a",
            "operation-a",
            "resource-a",
            DDTraceId.ONE,
            123L,
            0L,
            1000L,
            2000L,
            1,
            singletonMap(DECISION_MAKER_TAG, "-3"),
            tags,
            "web",
            false,
            SAMPLER_KEEP,
            200,
            "rum");

    byte[] encoded = serializeV1Payload(span);
    List<String> stringTable = newStringTable();

    try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded)) {
      int payloadFieldCount = unpacker.unpackMapHeader();
      Set<Integer> payloadFieldsSeen = new HashSet<>();
      int chunkCount = -1;
      Map<String, Object> payloadAttributes = null;

      for (int i = 0; i < payloadFieldCount; i++) {
        int fieldId = unpacker.unpackInt();
        payloadFieldsSeen.add(fieldId);
        if (fieldId == PayloadField.CHUNKS) {
          chunkCount = unpacker.unpackArrayHeader();
          assertEquals(1, chunkCount);
          verifyChunk(unpacker, singletonList(span), stringTable);
        } else if (fieldId == PayloadField.ATTRIBUTES) {
          payloadAttributes = readAttributes(unpacker, stringTable);
        } else {
          skipPayloadField(unpacker, fieldId, stringTable);
        }
      }

      assertEquals(EXPECTED_PAYLOAD_FIELD_IDS.size(), payloadFieldCount);
      assertEquals(EXPECTED_PAYLOAD_FIELD_IDS, payloadFieldsSeen);
      assertEquals(1, chunkCount);
      assertNotNull(payloadAttributes);
      CharSequence processTags = ProcessTags.getTagsForSerialization();
      if (processTags == null) {
        assertEquals(0, payloadAttributes.size());
      } else {
        assertEquals(1, payloadAttributes.size());
        assertEquals(processTags.toString(), payloadAttributes.get(PROCESS_TAGS));
      }
    }
  }

  // expectedSamplingMechanism 0 is SamplingMechanism.DEFAULT.
  @TableTest({
    "scenario                      | decisionMakerTag | expectedSamplingMechanism",
    "no _dd.p.dm tag               |                  | 0                        ",
    "negative numeric value        | '-3'             | 3                        ",
    "hashed prefix before the dash | '934086a686-7'   | 7                        ",
    "unparseable value             | invalid          | 0                        "
  })
  void samplingMechanismNormalizationFromDecisionMaker(
      String scenario, String decisionMakerTag, int expectedSamplingMechanism) throws IOException {
    Map<String, String> baggage = new HashMap<>();
    if (decisionMakerTag != null) {
      baggage.put(DECISION_MAKER_TAG, decisionMakerTag);
    }

    byte[] encoded = serializeV1Payload(spanWithBaggage(baggage));

    assertEquals(expectedSamplingMechanism, readFirstChunk(encoded).getSamplingMechanism());
  }

  @Test
  void spanIdsAreEncodedAsUnsignedValues() throws IOException {
    long spanId = Long.MIN_VALUE + 123L;
    long parentId = Long.MIN_VALUE + 456L;

    byte[] encoded = serializeV1Payload(spanWithIds(spanId, parentId));
    List<String> stringTable = newStringTable();

    Long actualSpanId = null;
    Long actualParentId = null;
    try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded)) {
      int payloadFieldCount = unpacker.unpackMapHeader();
      for (int i = 0; i < payloadFieldCount; i++) {
        int payloadFieldId = unpacker.unpackInt();
        if (payloadFieldId != PayloadField.CHUNKS) {
          skipPayloadField(unpacker, payloadFieldId, stringTable);
          continue;
        }
        assertEquals(1, unpacker.unpackArrayHeader());

        int chunkFieldCount = unpacker.unpackMapHeader();
        for (int j = 0; j < chunkFieldCount; j++) {
          int chunkFieldId = unpacker.unpackInt();
          if (chunkFieldId != ChunkField.SPANS) {
            skipChunkField(unpacker, chunkFieldId, stringTable);
            continue;
          }
          assertEquals(1, unpacker.unpackArrayHeader());

          int spanFieldCount = unpacker.unpackMapHeader();
          for (int k = 0; k < spanFieldCount; k++) {
            int spanFieldId = unpacker.unpackInt();
            switch (spanFieldId) {
              case SpanField.SPAN_ID:
                actualSpanId = unpackUint64(unpacker);
                break;
              case SpanField.PARENT_ID:
                actualParentId = unpackUint64(unpacker);
                break;
              default:
                skipSpanField(unpacker, spanFieldId, stringTable);
            }
          }
        }
      }
    }

    assertEquals(spanId, actualSpanId);
    assertEquals(parentId, actualParentId);
  }

  @Test
  void spanLinksAreEncodedFromStructuredSpanLinks() throws IOException {
    DDTraceId firstLinkTraceId = DDTraceId.fromHex("11223344556677889900aabbccddeeff");
    long firstLinkSpanId = DDSpanId.fromHex("000000000000002a");
    Map<String, String> firstLinkAttributes = new HashMap<>();
    firstLinkAttributes.put("link.kind", "follows_from");
    firstLinkAttributes.put("context_headers", "tracecontext");
    DDTraceId secondLinkTraceId = DDTraceId.fromHex("00000000000000000000000000000001");
    long secondLinkSpanId = DDSpanId.fromHex("0000000000000002");
    List<AgentSpanLink> spanLinks = new ArrayList<>();
    spanLinks.add(
        new TestSpanLink(
            firstLinkTraceId,
            firstLinkSpanId,
            (byte) 1,
            "dd=s:1",
            SpanAttributes.fromMap(firstLinkAttributes)));
    spanLinks.add(
        new TestSpanLink(secondLinkTraceId, secondLinkSpanId, (byte) 0, "", SpanAttributes.EMPTY));

    List<V1SpanLink> links = readFirstSpan(serializeV1Payload(spanWithLinks(spanLinks))).getLinks();

    assertEquals(2, links.size());
    V1SpanLink firstLink = links.get(0);
    assertArrayEquals(traceIdBytes(firstLinkTraceId), firstLink.getTraceId());
    assertEquals(firstLinkSpanId, firstLink.getSpanId());
    assertEquals("dd=s:1", firstLink.getTraceState());
    assertEquals(1L, firstLink.getTraceFlags());
    assertEquals(firstLinkAttributes, firstLink.getAttributes());

    V1SpanLink secondLink = links.get(1);
    assertArrayEquals(traceIdBytes(secondLinkTraceId), secondLink.getTraceId());
    assertEquals(secondLinkSpanId, secondLink.getSpanId());
    assertEquals("", secondLink.getTraceState());
    assertEquals(0L, secondLink.getTraceFlags());
    assertEquals(emptyMap(), secondLink.getAttributes());
  }

  @Test
  void firstSpanTagsAreProcessedOnce() {
    CountingPojoSpan firstSpan = new CountingPojoSpan("operation-a", "resource-a", 123L, 0L);
    CountingPojoSpan secondSpan = new CountingPojoSpan("operation-b", "resource-b", 456L, 123L);
    List<PojoSpan> trace = new ArrayList<>();
    trace.add(firstSpan);
    trace.add(secondSpan);

    serializeV1Payload(trace);

    assertEquals(1, firstSpan.processTagsAndBaggageCount);
    assertEquals(1, secondSpan.processTagsAndBaggageCount);
  }

  @Test
  void missingSpanLinksEncodeEmptyLinks() throws IOException {
    byte[] encoded = serializeV1Payload(span(emptyMap()));

    assertTrue(readFirstSpan(encoded).getLinks().isEmpty());
  }

  @Test
  void spanEventsAreEncodedFromEventsTag() throws IOException {
    Map<String, Object> firstEventAttributes = new HashMap<>();
    firstEventAttributes.put("str", "v");
    firstEventAttributes.put("int", 42L);
    firstEventAttributes.put("double", 12.5d);
    firstEventAttributes.put("bool", true);
    firstEventAttributes.put("arr", asList("x", 7L, 2.5d, false));
    Map<String, Object> firstEvent = new HashMap<>();
    firstEvent.put("time_unix_nano", 1234567890L);
    firstEvent.put("name", "event.one");
    firstEvent.put("attributes", firstEventAttributes);
    Map<String, Object> secondEvent = new HashMap<>();
    secondEvent.put("time_unix_nano", 1234567891L);
    secondEvent.put("name", "event.two");
    PojoSpan span = span(singletonMap(SPAN_EVENTS, asList(firstEvent, secondEvent)));

    List<V1SpanEvent> events = readFirstSpan(serializeV1Payload(span)).getEvents();

    assertEquals(2, events.size());
    V1SpanEvent firstDecodedEvent = events.get(0);
    assertEquals(1234567890L, firstDecodedEvent.getTimeUnixNano());
    assertEquals("event.one", firstDecodedEvent.getName());
    Map<String, Object> firstDecodedAttributes = firstDecodedEvent.getAttributes();
    assertEquals("v", firstDecodedAttributes.get("str"));
    assertEquals(42L, firstDecodedAttributes.get("int"));
    assertAttributeValueEquals(12.5d, firstDecodedAttributes.get("double"), "double");
    assertEquals(true, firstDecodedAttributes.get("bool"));
    assertEquals(asList("x", 7L, 2.5d, false), firstDecodedAttributes.get("arr"));

    V1SpanEvent secondDecodedEvent = events.get(1);
    assertEquals(1234567891L, secondDecodedEvent.getTimeUnixNano());
    assertEquals("event.two", secondDecodedEvent.getName());
    assertEquals(emptyMap(), secondDecodedEvent.getAttributes());
  }

  @Test
  void malformedSpanEventsFallBackToEmptyEvents() throws IOException {
    // a map instead of the expected list of events
    PojoSpan span = span(singletonMap(SPAN_EVENTS, singletonMap("foo", "bar")));

    assertTrue(readFirstSpan(serializeV1Payload(span)).getEvents().isEmpty());
  }

  @Test
  void metaStructIsEncodedAsBytesAttribute() throws IOException {
    PojoSpan span = span(emptyMap());
    Map<String, Object> metaStructValue = new HashMap<>();
    metaStructValue.put("foo", "bar");
    metaStructValue.put("answer", 42L);
    span.setMetaStruct("meta_key", metaStructValue);

    Map<String, Object> attributes = readFirstSpan(serializeV1Payload(span)).getAttributes();
    byte[] metaStructBytes = (byte[]) attributes.get("meta_key");
    assertNotNull(metaStructBytes);

    Map<String, Object> decodedMetaStruct = new HashMap<>();
    try (MessageUnpacker metaStructUnpacker = MessagePack.newDefaultUnpacker(metaStructBytes)) {
      int metaStructFieldCount = metaStructUnpacker.unpackMapHeader();
      for (int i = 0; i < metaStructFieldCount; i++) {
        String key = metaStructUnpacker.unpackString();
        ValueType valueType = metaStructUnpacker.getNextFormat().getValueType();
        switch (valueType) {
          case INTEGER:
            decodedMetaStruct.put(key, metaStructUnpacker.unpackLong());
            break;
          case STRING:
            decodedMetaStruct.put(key, metaStructUnpacker.unpackString());
            break;
          default:
            fail("Unexpected meta_struct value type for key " + key);
        }
      }
    }

    assertEquals("bar", decodedMetaStruct.get("foo"));
    assertEquals(42L, decodedMetaStruct.get("answer"));
  }

  @Test
  void mapValuedSpanTagsAreFlattenedInAttributes() throws IOException {
    Map<String, Object> user = new HashMap<>();
    user.put("id", "123");
    user.put("name", "alice");
    user.put("authenticated", true);
    user.put("profile", singletonMap("age", 30L));
    Map<String, Object> loginMetadata = new HashMap<>();
    loginMetadata.put("event", "login");
    loginMetadata.put("attempts", 1L);
    Map<String, Object> loginSuccess = new HashMap<>();
    loginSuccess.put("metadata0", loginMetadata);
    loginSuccess.put("metadata1", singletonMap("blocked", false));
    Map<String, Object> tags = new HashMap<>();
    tags.put("usr", user);
    String loginSuccessTag = "appsec.events.users.login.success";
    tags.put(loginSuccessTag, loginSuccess);

    // status code 0 keeps the encoder from adding an http.status_code attribute
    Map<String, Object> attributes =
        readFirstSpan(serializeV1Payload(span(tags, 0))).getAttributes();

    assertEquals("123", attributes.get("usr.id"));
    assertEquals("alice", attributes.get("usr.name"));
    assertEquals(true, attributes.get("usr.authenticated"));
    assertAttributeValueEquals(30L, attributes.get("usr.profile.age"), "usr.profile.age");
    assertEquals("login", attributes.get(loginSuccessTag + ".metadata0.event"));
    assertAttributeValueEquals(
        1L, attributes.get(loginSuccessTag + ".metadata0.attempts"), "attempts");
    assertEquals(false, attributes.get(loginSuccessTag + ".metadata1.blocked"));
    // the 7 flattened entries plus thread.id and thread.name, and nothing else
    assertEquals(9, attributes.size());

    // the map-valued tags themselves are replaced by their flattened entries
    assertFalse(attributes.containsKey("usr"));
    assertFalse(attributes.containsKey(loginSuccessTag));
  }

  @Test
  void primitiveSpanTagsAreEncodedInAttributes() throws IOException {
    Map<String, Object> tags = new HashMap<>();
    tags.put("tag.bool", true);
    tags.put("tag.int", 7);
    tags.put("tag.long", 9L);
    tags.put("tag.float", 3.5f);
    tags.put("tag.double", 4.25d);

    // status code 0 keeps the encoder from adding an http.status_code attribute
    Map<String, Object> attributes =
        readFirstSpan(serializeV1Payload(span(tags, 0))).getAttributes();

    assertEquals(true, attributes.get("tag.bool"));
    assertAttributeValueEquals(7, attributes.get("tag.int"), "tag.int");
    assertAttributeValueEquals(9L, attributes.get("tag.long"), "tag.long");
    assertAttributeValueEquals(3.5f, attributes.get("tag.float"), "tag.float");
    assertAttributeValueEquals(4.25d, attributes.get("tag.double"), "tag.double");
    // the 5 tags plus thread.id and thread.name, and nothing else
    assertEquals(7, attributes.size());
  }

  @Test
  void threadMetadataIsEncodedInAttributes() throws IOException {
    // status code 0 keeps the encoder from adding an http.status_code attribute
    PojoSpan span = span(emptyMap(), 0);

    Map<String, Object> attributes = readFirstSpan(serializeV1Payload(span)).getAttributes();

    assertAttributeValueEquals(span.getTag(THREAD_ID), attributes.get(THREAD_ID), THREAD_ID);
    Object expectedThreadName = span.getTag(THREAD_NAME);
    assertEquals(expectedThreadName.toString(), attributes.get(THREAD_NAME));
    // thread metadata is all a tagless span encodes
    assertEquals(2, attributes.size());
  }

  private static final class PayloadVerifier implements ByteBufferConsumer {

    private final List<List<PojoSpan>> expectedTraces;
    private final TraceMapperV1 mapper;
    private final PayloadVerifiers.CapturingChannel channel =
        new PayloadVerifiers.CapturingChannel(200 << 10);

    private int position = 0;

    private PayloadVerifier(List<List<PojoSpan>> expectedTraces, TraceMapperV1 mapper) {
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
        payload.writeTo(channel);
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(channel.flipForReading())) {
          if (messageCount == 0) {
            assertEquals(0, unpacker.unpackMapHeader());
            return;
          }

          List<String> stringTable = newStringTable();
          int payloadFieldCount = unpacker.unpackMapHeader();
          assertEquals(EXPECTED_PAYLOAD_FIELD_IDS.size(), payloadFieldCount);

          boolean seenChunks = false;
          for (int i = 0; i < payloadFieldCount; i++) {
            int fieldId = unpacker.unpackInt();
            if (fieldId == PayloadField.CHUNKS) {
              int traceCount = unpacker.unpackArrayHeader();
              assertEquals(messageCount, traceCount);
              seenChunks = true;
              for (int traceIndex = 0; traceIndex < traceCount; traceIndex++) {
                verifyChunk(unpacker, expectedTraces.get(position++), stringTable);
              }
            } else {
              skipPayloadField(unpacker, fieldId, stringTable);
            }
          }

          assertTrue(seenChunks);
        }
      } catch (IOException e) {
        fail(e.getMessage());
      } finally {
        mapper.reset();
        channel.resetForWriting();
      }
    }

    void verifyTracesConsumed() {
      assertEquals(expectedTraces.size(), position);
    }
  }

  private static void verifyChunk(
      MessageUnpacker unpacker, List<PojoSpan> expectedTrace, List<String> stringTable)
      throws IOException {
    int chunkFieldCount = unpacker.unpackMapHeader();
    assertEquals(6, chunkFieldCount);

    Integer priority = null;
    String origin = null;
    Map<String, Object> chunkAttributes = null;
    byte[] traceId = null;
    Integer samplingMechanism = null;
    boolean seenSpans = false;

    for (int i = 0; i < chunkFieldCount; i++) {
      int fieldId = unpacker.unpackInt();
      switch (fieldId) {
        case ChunkField.PRIORITY:
          priority = unpacker.unpackInt();
          break;
        case ChunkField.ORIGIN:
          origin = readStreamingString(unpacker, stringTable);
          break;
        case ChunkField.ATTRIBUTES:
          chunkAttributes = readAttributes(unpacker, stringTable);
          break;
        case ChunkField.SPANS:
          verifySpans(unpacker, expectedTrace, stringTable);
          seenSpans = true;
          break;
        case ChunkField.TRACE_ID:
          traceId = readBinary(unpacker);
          break;
        case ChunkField.SAMPLING_MECHANISM:
          samplingMechanism = unpacker.unpackInt();
          break;
        default:
          fail("Unexpected chunk field id: " + fieldId);
      }
    }

    assertNotNull(priority);
    assertNotNull(origin);
    assertNotNull(chunkAttributes);
    assertTrue(seenSpans);
    assertNotNull(traceId);
    assertNotNull(samplingMechanism);

    PojoSpan firstSpan = expectedTrace.get(0);
    assertEquals(firstSpan.samplingPriority(), priority.intValue());
    assertEqualsWithNullAsEmpty(firstSpan.getOrigin(), origin);
    assertEquals(1, chunkAttributes.size());
    assertEqualsWithNullAsEmpty(
        firstSpan.getLocalRootSpan().getServiceName(), (String) chunkAttributes.get("service"));
    assertArrayEquals(traceIdBytes(firstSpan.getTraceId()), traceId);
    assertEquals(expectedSamplingMechanism(firstSpan.getBaggage()), samplingMechanism.intValue());
  }

  private static void verifySpans(
      MessageUnpacker unpacker, List<PojoSpan> expectedTrace, List<String> stringTable)
      throws IOException {
    int spanCount = unpacker.unpackArrayHeader();
    assertEquals(expectedTrace.size(), spanCount);

    for (int i = 0; i < spanCount; i++) {
      verifySpan(unpacker, expectedTrace.get(i), stringTable);
    }
  }

  private static void verifySpan(
      MessageUnpacker unpacker, PojoSpan expectedSpan, List<String> stringTable)
      throws IOException {
    int spanFieldCount = unpacker.unpackMapHeader();
    assertEquals(EXPECTED_SPAN_FIELD_IDS.size(), spanFieldCount);

    Set<Integer> spanFieldsSeen = new HashSet<>();
    String service = null;
    String name = null;
    String resource = null;
    long spanId = 0;
    long parentId = 0;
    long start = 0;
    long duration = 0;
    boolean error = false;
    Map<String, Object> attributes = null;
    String type = null;
    int linksCount = -1;
    int eventsCount = -1;
    String env = null;
    String version = null;
    String component = null;
    int spanKind = -1;

    for (int i = 0; i < spanFieldCount; i++) {
      int fieldId = unpacker.unpackInt();
      spanFieldsSeen.add(fieldId);
      switch (fieldId) {
        case SpanField.SERVICE:
          service = readStreamingString(unpacker, stringTable);
          break;
        case SpanField.NAME:
          name = readStreamingString(unpacker, stringTable);
          break;
        case SpanField.RESOURCE:
          resource = readStreamingString(unpacker, stringTable);
          break;
        case SpanField.SPAN_ID:
          spanId = unpackUnsignedLong(unpacker);
          break;
        case SpanField.PARENT_ID:
          parentId = unpackUnsignedLong(unpacker);
          break;
        case SpanField.START:
          start = unpacker.unpackLong();
          break;
        case SpanField.DURATION:
          duration = unpacker.unpackLong();
          break;
        case SpanField.ERROR:
          error = unpacker.unpackBoolean();
          break;
        case SpanField.ATTRIBUTES:
          attributes = readAttributes(unpacker, stringTable);
          break;
        case SpanField.TYPE:
          type = readStreamingString(unpacker, stringTable);
          break;
        case SpanField.LINKS:
          linksCount = unpacker.unpackArrayHeader();
          break;
        case SpanField.EVENTS:
          eventsCount = unpacker.unpackArrayHeader();
          break;
        case SpanField.ENV:
          env = readStreamingString(unpacker, stringTable);
          break;
        case SpanField.VERSION:
          version = readStreamingString(unpacker, stringTable);
          break;
        case SpanField.COMPONENT:
          component = readStreamingString(unpacker, stringTable);
          break;
        case SpanField.KIND:
          spanKind = unpacker.unpackInt();
          break;
        default:
          fail("Unexpected span field id: " + fieldId);
      }
    }

    // A 16-entry map could still repeat one field id and omit another, which would leave a decoded
    // value at its initial sentinel. Pinning the id set makes each field present exactly once, so
    // the value assertions below cannot pass on an unwritten field (e.g. parentId 0, error false).
    assertEquals(EXPECTED_SPAN_FIELD_IDS, spanFieldsSeen);

    assertEqualsWithNullAsEmpty(expectedSpan.getServiceName(), service);
    assertEqualsWithNullAsEmpty(expectedSpan.getOperationName(), name);
    assertEqualsWithNullAsEmpty(expectedSpan.getResourceName(), resource);
    assertEquals(expectedSpan.getSpanId(), spanId);
    assertEquals(expectedSpan.getParentId(), parentId);
    assertEquals(expectedSpan.getStartTime(), start);
    assertEquals(expectedSpan.getDurationNano(), duration);
    assertEquals(expectedSpan.getError() != 0, error);
    assertEqualsWithNullAsEmpty(expectedSpan.getType(), type);
    assertEquals(0, linksCount);
    assertEquals(0, eventsCount);
    assertEqualsWithNullAsEmpty(expectedSpan.getTag(ENV), env);
    assertEqualsWithNullAsEmpty(expectedSpan.getTag(VERSION), version);
    assertEqualsWithNullAsEmpty(expectedSpan.getTag(COMPONENT), component);
    assertEquals(TraceMapperV1.getSpanKindValue(expectedSpan.getTag(SPAN_KIND)), spanKind);

    assertNotNull(attributes);
    int expectedHttpStatusCode = expectedSpan.getHttpStatusCode();
    boolean shouldContainHttpStatus =
        expectedHttpStatusCode != 0 && !expectedSpan.getTags().containsKey(HTTP_STATUS);
    Map<String, Object> expectedAttributes = new HashMap<>(expectedSpan.getBaggage());
    expectedAttributes.put(THREAD_ID, expectedSpan.getTag(THREAD_ID));
    expectedAttributes.put(THREAD_NAME, expectedSpan.getTag(THREAD_NAME));
    for (Map.Entry<String, Object> entry : expectedSpan.getTags().entrySet()) {
      if (SPAN_EVENTS.equals(entry.getKey())) {
        continue;
      }
      addFlattenedExpectedAttribute(expectedAttributes, entry.getKey(), entry.getValue());
    }
    if (shouldContainHttpStatus) {
      expectedAttributes.put(HTTP_STATUS, Integer.toString(expectedHttpStatusCode));
    }
    if (expectedSpan.isTopLevel()) {
      expectedAttributes.put(InstrumentationTags.DD_TOP_LEVEL.toString(), 1d);
    }

    assertEquals(expectedAttributes.size(), attributes.size());
    for (Map.Entry<String, Object> entry : expectedAttributes.entrySet()) {
      String key = entry.getKey();
      assertTrue(attributes.containsKey(key), "Missing attribute key: " + key);
      assertAttributeValueEquals(entry.getValue(), attributes.get(key), key);
    }
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

  private static int expectedSamplingMechanism(Map<String, String> propagationMetadata) {
    String decisionMaker = propagationMetadata.get(DECISION_MAKER_TAG);
    if (decisionMaker == null) {
      return SamplingMechanism.DEFAULT;
    }

    try {
      return Math.abs(Integer.parseInt(decisionMaker));
    } catch (NumberFormatException ignored) {
      int separator = decisionMaker.lastIndexOf('-');
      if (separator >= 0 && separator + 1 < decisionMaker.length()) {
        try {
          return Math.abs(Integer.parseInt(decisionMaker.substring(separator + 1)));
        } catch (NumberFormatException ignoredAgain) {
          // not a mechanism suffix either, fall through to the default
        }
      }
      return SamplingMechanism.DEFAULT;
    }
  }

  /** Asserts the next value is encoded as an unsigned 64-bit integer, and returns it. */
  private static long unpackUint64(MessageUnpacker unpacker) throws IOException {
    assertEquals(MessageFormat.UINT64, unpacker.getNextFormat());
    return unpackUnsignedLong(unpacker);
  }

  private static byte[] serializeV1Payload(PojoSpan span) {
    return serializeV1Payload(singletonList(span));
  }

  /** Maps a single trace and returns the complete v1 payload bytes. */
  private static byte[] serializeV1Payload(List<PojoSpan> trace) {
    TraceMapperV1 mapper = new TraceMapperV1();
    CapturedBody capturedBody = new CapturedBody(mapper);
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(2 << 20, capturedBody));

    assertTrue(packer.format(trace, mapper));
    packer.flush();

    assertNotNull(capturedBody.payloadBytes);
    return capturedBody.payloadBytes;
  }

  private static PojoSpan span(Map<String, Object> tags) {
    return span(123L, 0L, emptyMap(), tags, 200, emptyList());
  }

  private static PojoSpan span(Map<String, Object> tags, int statusCode) {
    return span(123L, 0L, emptyMap(), tags, statusCode, emptyList());
  }

  private static PojoSpan spanWithBaggage(Map<String, String> baggage) {
    return span(123L, 0L, baggage, emptyMap(), 200, emptyList());
  }

  private static PojoSpan spanWithIds(long spanId, long parentId) {
    return span(spanId, parentId, emptyMap(), emptyMap(), 200, emptyList());
  }

  private static PojoSpan spanWithLinks(List<AgentSpanLink> spanLinks) {
    return span(123L, 0L, emptyMap(), emptyMap(), 200, spanLinks);
  }

  /** A span carrying the fields shared by most tests; only the varying pieces are parameters. */
  private static PojoSpan span(
      long spanId,
      long parentId,
      Map<String, String> baggage,
      Map<String, Object> tags,
      int statusCode,
      List<AgentSpanLink> spanLinks) {
    return new PojoSpan(
        "service-a",
        "operation-a",
        "resource-a",
        DDTraceId.ONE,
        spanId,
        parentId,
        1000L,
        2000L,
        0,
        baggage,
        tags,
        "web",
        false,
        SAMPLER_KEEP,
        statusCode,
        null,
        spanLinks);
  }

  private static final class CapturedBody implements ByteBufferConsumer {
    private final TraceMapperV1 mapper;
    private byte[] payloadBytes;

    private CapturedBody(TraceMapperV1 mapper) {
      this.mapper = mapper;
    }

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try {
        Payload payload = mapper.newPayload().withBody(messageCount, buffer);
        payload.writeTo(Channels.newChannel(out));
        payloadBytes = out.toByteArray();
      } catch (IOException e) {
        fail("Failed to serialize the v1 payload: " + e.getMessage());
      } finally {
        mapper.reset();
      }
    }
  }

  /** A span counting how many times its tags and baggage were handed to the mapper. */
  private static final class CountingPojoSpan extends PojoSpan {
    private int processTagsAndBaggageCount = 0;

    private CountingPojoSpan(
        String operationName, CharSequence resourceName, long spanId, long parentId) {
      super(
          "service-a",
          operationName,
          resourceName,
          DDTraceId.ONE,
          spanId,
          parentId,
          1000L,
          2000L,
          0,
          emptyMap(),
          singletonMap(HTTP_URL, "http://localhost:7777/"),
          "web",
          false,
          SAMPLER_KEEP,
          200,
          null);
    }

    @Override
    public void processTagsAndBaggage(MetadataConsumer consumer) {
      processTagsAndBaggageCount++;
      super.processTagsAndBaggage(consumer);
    }
  }

  /** {@link SpanLink}'s constructor is protected, so tests reach it through a subclass. */
  private static final class TestSpanLink extends SpanLink {
    private TestSpanLink(
        DDTraceId traceId,
        long spanId,
        byte traceFlags,
        String traceState,
        SpanAttributes attributes) {
      super(traceId, spanId, traceFlags, traceState, attributes);
    }
  }
}
