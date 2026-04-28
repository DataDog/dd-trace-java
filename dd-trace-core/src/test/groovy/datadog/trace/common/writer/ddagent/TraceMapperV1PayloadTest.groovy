package datadog.trace.common.writer.ddagent

import static datadog.trace.common.writer.TraceGenerator.generateRandomTraces
import static org.junit.jupiter.api.Assertions.assertArrayEquals
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.msgpack.core.MessageFormat.FIXSTR
import static org.msgpack.core.MessageFormat.STR16
import static org.msgpack.core.MessageFormat.STR32
import static org.msgpack.core.MessageFormat.STR8

import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.api.DDTags
import datadog.trace.api.DDTraceId
import datadog.trace.api.DDSpanId
import datadog.trace.api.ProcessTags
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.sampling.SamplingMechanism
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes
import datadog.trace.bootstrap.instrumentation.api.SpanLink
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.writer.Payload
import datadog.trace.common.writer.TraceGenerator
import datadog.trace.core.MetadataConsumer
import datadog.trace.test.util.DDSpecification
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import org.junit.jupiter.api.Assertions
import org.msgpack.core.MessageFormat
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker

class TraceMapperV1PayloadTest extends DDSpecification {

  def "test traces written correctly"() {
    setup:
    List<List<TraceGenerator.PojoSpan>> traces = generateRandomTraces(traceCount, lowCardinality)
    TraceMapperV1 traceMapper = new TraceMapperV1()
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper)
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(bufferSize, verifier))

    when:
    boolean tracesFitInBuffer = true
    for (List<TraceGenerator.PojoSpan> trace : traces) {
      if (!packer.format(trace, traceMapper)) {
        verifier.skipLargeTrace()
        tracesFitInBuffer = false
        traceMapper.reset()
      }
    }
    packer.flush()

    then:
    if (tracesFitInBuffer) {
      verifier.verifyTracesConsumed()
    }

    where:
    bufferSize | traceCount | lowCardinality
    20 << 10   | 0          | true
    20 << 10   | 1          | true
    30 << 10   | 2          | true
    20 << 10   | 0          | false
    20 << 10   | 1          | false
    30 << 10   | 2          | false
    100 << 10  | 10         | true
    100 << 10  | 100        | false
  }

  def "test endpoint returns v1.0"() {
    expect:
    new TraceMapperV1().endpoint() == "v1.0"
  }

  def "test span kind value conversion"() {
    expect:
    TraceMapperV1.getSpanKindValue(null) == TraceMapperV1.SPAN_KIND_UNSPECIFIED
    TraceMapperV1.getSpanKindValue(Tags.SPAN_KIND_INTERNAL) == TraceMapperV1.SPAN_KIND_INTERNAL
    TraceMapperV1.getSpanKindValue(Tags.SPAN_KIND_SERVER) == TraceMapperV1.SPAN_KIND_SERVER
    TraceMapperV1.getSpanKindValue(Tags.SPAN_KIND_CLIENT) == TraceMapperV1.SPAN_KIND_CLIENT
    TraceMapperV1.getSpanKindValue(Tags.SPAN_KIND_PRODUCER) == TraceMapperV1.SPAN_KIND_PRODUCER
    TraceMapperV1.getSpanKindValue(Tags.SPAN_KIND_CONSUMER) == TraceMapperV1.SPAN_KIND_CONSUMER
    TraceMapperV1.getSpanKindValue("unknown") == TraceMapperV1.SPAN_KIND_INTERNAL
  }

  def "test payload contains expected header and chunk fields"() {
    setup:
    Map<String, Object> tags = [
      (Tags.ENV): "prod",
      (Tags.VERSION): "1.2.3",
      (Tags.COMPONENT): "http-client",
      (Tags.SPAN_KIND): Tags.SPAN_KIND_CLIENT,
      "attr.string": "value",
      "attr.bool"  : true,
      "attr.number": 12.5d,
      "_dd.p.dm"   : "-3"
    ]
    def span = new TraceGenerator.PojoSpan(
      "service-a",
      "operation-a",
      "resource-a",
      DDTraceId.ONE,
      123L,
      0L,
      1000L,
      2000L,
      1,
      [:],
      tags,
      "web",
      false,
      PrioritySampling.SAMPLER_KEEP,
      200,
      "rum")

    TraceMapperV1 mapper = new TraceMapperV1()
    byte[] encoded = serializeMappedPayload(mapper, [[span]])
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded)
    List<String> stringTable = new ArrayList<>()
    stringTable.add("")

    when:
    int payloadFieldCount = unpacker.unpackMapHeader()
    Set<Integer> payloadFieldsSeen = new HashSet<>()
    int chunkCount = -1
    Map<String, Object> payloadAttributes = null

    for (int i = 0; i < payloadFieldCount; i++) {
      int fieldId = unpacker.unpackInt()
      payloadFieldsSeen.add(fieldId)
      switch (fieldId) {
        case 2:
        case 3:
        case 4:
        case 5:
        case 6:
        case 7:
        case 8:
        case 9:
          readStreamingString(unpacker, stringTable)
          break
        case 10:
          payloadAttributes = readAttributes(unpacker, stringTable)
          break
        case 11:
          chunkCount = unpacker.unpackArrayHeader()
          assertEquals(1, chunkCount)
          verifyChunk(unpacker, [span], stringTable)
          break
        default:
          Assertions.fail("Unexpected payload field id: " + fieldId)
      }
    }

    then:
    assertEquals(10, payloadFieldCount)
    assertEquals((2..11).toSet(), payloadFieldsSeen)
    assertEquals(1, chunkCount)
    assertNotNull(payloadAttributes)
    if (ProcessTags.tagsForSerialization == null) {
      assertEquals(0, payloadAttributes.size())
    } else {
      assertEquals(1, payloadAttributes.size())
      assertEquals(ProcessTags.tagsForSerialization.toString(), payloadAttributes.get(DDTags.PROCESS_TAGS))
    }
  }

  def "test sampling mechanism normalization from _dd.p.dm"() {
    setup:
    def span = new TraceGenerator.PojoSpan(
      "service-a",
      "operation-a",
      "resource-a",
      DDTraceId.ONE,
      321L,
      0L,
      1000L,
      2000L,
      0,
      [:],
      decisionMakerTag == null ? [:] : ["_dd.p.dm": decisionMakerTag],
      "custom",
      false,
      PrioritySampling.SAMPLER_KEEP,
      200,
      null)

    TraceMapperV1 mapper = new TraceMapperV1()
    byte[] encoded = serializeMappedPayload(mapper, [[span]])
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded)
    List<String> stringTable = new ArrayList<>()
    stringTable.add("")

    when:
    unpacker.unpackMapHeader()
    int samplingMechanism = -1

    for (int i = 0; i < 10; i++) {
      int payloadFieldId = unpacker.unpackInt()
      if (payloadFieldId == 11) {
        int chunkCount = unpacker.unpackArrayHeader()
        assertEquals(1, chunkCount)
        int chunkFieldCount = unpacker.unpackMapHeader()
        for (int j = 0; j < chunkFieldCount; j++) {
          int chunkFieldId = unpacker.unpackInt()
          if (chunkFieldId == 7) {
            samplingMechanism = unpacker.unpackInt()
          } else {
            skipChunkField(unpacker, chunkFieldId, stringTable)
          }
        }
      } else {
        skipPayloadField(unpacker, payloadFieldId, stringTable)
      }
    }

    then:
    assertEquals(expectedSamplingMechanism, samplingMechanism)

    where:
    decisionMakerTag | expectedSamplingMechanism
    null             | SamplingMechanism.DEFAULT
    "-3"            | 3
    "934086a686-7"  | 7
    "invalid"       | SamplingMechanism.DEFAULT
  }

  def "test span ids are encoded as unsigned values in v1 payloads"() {
    setup:
    long spanId = Long.MIN_VALUE + 123L
    long parentId = Long.MIN_VALUE + 456L
    def span = new TraceGenerator.PojoSpan(
      "service-a",
      "operation-a",
      "resource-a",
      DDTraceId.ONE,
      spanId,
      parentId,
      1000L,
      2000L,
      0,
      [:],
      [:],
      "web",
      false,
      PrioritySampling.SAMPLER_KEEP,
      200,
      null)

    TraceMapperV1 mapper = new TraceMapperV1()
    byte[] encoded = serializeMappedPayload(mapper, [[span]])
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded)
    List<String> stringTable = new ArrayList<>()
    stringTable.add("")

    when:
    unpacker.unpackMapHeader()
    Long actualSpanId = null
    Long actualParentId = null

    for (int i = 0; i < 10; i++) {
      int payloadFieldId = unpacker.unpackInt()
      if (payloadFieldId == 11) {
        int chunkCount = unpacker.unpackArrayHeader()
        assertEquals(1, chunkCount)
        int chunkFieldCount = unpacker.unpackMapHeader()
        for (int j = 0; j < chunkFieldCount; j++) {
          int chunkFieldId = unpacker.unpackInt()
          if (chunkFieldId == 4) {
            int spanCount = unpacker.unpackArrayHeader()
            assertEquals(1, spanCount)
            int spanFieldCount = unpacker.unpackMapHeader()
            for (int k = 0; k < spanFieldCount; k++) {
              int spanFieldId = unpacker.unpackInt()
              switch (spanFieldId) {
                case 4:
                  assertEquals(MessageFormat.UINT64, unpacker.nextFormat)
                  actualSpanId = DDSpanId.from("${unpacker.unpackBigInteger()}")
                  break
                case 5:
                  assertEquals(MessageFormat.UINT64, unpacker.nextFormat)
                  actualParentId = DDSpanId.from("${unpacker.unpackBigInteger()}")
                  break
                default:
                  skipSpanField(unpacker, spanFieldId, stringTable)
              }
            }
          } else {
            skipChunkField(unpacker, chunkFieldId, stringTable)
          }
        }
      } else {
        skipPayloadField(unpacker, payloadFieldId, stringTable)
      }
    }

    then:
    assertEquals(spanId, actualSpanId)
    assertEquals(parentId, actualParentId)
  }

  def "test span links are encoded from structured span links"() {
    setup:
    List<SpanLink> spanLinks = [
      new SpanLink(
      DDTraceId.fromHex("11223344556677889900aabbccddeeff"),
      DDSpanId.fromHex("000000000000002a"),
      (byte) 1,
      "dd=s:1",
      SpanAttributes.fromMap(["link.kind": "follows_from", "context_headers": "tracecontext"])),
      new SpanLink(
      DDTraceId.fromHex("00000000000000000000000000000001"),
      DDSpanId.fromHex("0000000000000002"),
      (byte) 0,
      "",
      SpanAttributes.EMPTY)
    ]
    def span = new TraceGenerator.PojoSpan(
      "service-a",
      "operation-a",
      "resource-a",
      DDTraceId.ONE,
      123L,
      0L,
      1000L,
      2000L,
      0,
      [:],
      [:],
      "web",
      false,
      PrioritySampling.SAMPLER_KEEP,
      200,
      null,
      spanLinks)

    TraceMapperV1 mapper = new TraceMapperV1()
    byte[] encoded = serializeMappedPayload(mapper, [[span]])
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded)
    List<String> stringTable = new ArrayList<>()
    stringTable.add("")

    when:
    List<Map<String, Object>> links = readFirstSpanLinks(unpacker, stringTable)

    then:
    assertEquals(2, links.size())
    assertArrayEquals(traceIdBytes(DDTraceId.fromHex("11223344556677889900aabbccddeeff")), links[0].traceId as byte[])
    assertEquals(DDSpanId.fromHex("000000000000002a"), links[0].spanId)
    assertEquals("dd=s:1", links[0].tracestate)
    assertEquals(1L, links[0].flags)
    assertEquals(["link.kind": "follows_from", "context_headers": "tracecontext"], links[0].attributes)

    assertArrayEquals(traceIdBytes(DDTraceId.fromHex("00000000000000000000000000000001")), links[1].traceId as byte[])
    assertEquals(DDSpanId.fromHex("0000000000000002"), links[1].spanId)
    assertEquals("", links[1].tracestate)
    assertEquals(0L, links[1].flags)
    assertEquals([:], links[1].attributes)
  }

  def "test first span tags are processed once"() {
    setup:
    def firstSpan = new CountingPojoSpan(
      "service-a",
      "operation-a",
      "resource-a",
      DDTraceId.ONE,
      123L,
      0L,
      1000L,
      2000L,
      0,
      [:],
      [(Tags.HTTP_URL): "http://localhost:7777/"],
      "web",
      false,
      PrioritySampling.SAMPLER_KEEP,
      200,
      null)

    def secondSpan = new CountingPojoSpan(
      "service-a",
      "operation-b",
      "resource-b",
      DDTraceId.ONE,
      456L,
      123L,
      1000L,
      2000L,
      0,
      [:],
      [(Tags.HTTP_URL): "http://localhost:7777/"],
      "web",
      false,
      PrioritySampling.SAMPLER_KEEP,
      200,
      null)

    TraceMapperV1 mapper = new TraceMapperV1()

    when:
    serializeMappedPayload(mapper, [[firstSpan, secondSpan]])

    then:
    assertEquals(1, firstSpan.processTagsAndBaggageCount)
    assertEquals(1, secondSpan.processTagsAndBaggageCount)
  }

  def "test missing span links encode empty links"() {
    setup:
    def span = new TraceGenerator.PojoSpan(
      "service-a",
      "operation-a",
      "resource-a",
      DDTraceId.ONE,
      123L,
      0L,
      1000L,
      2000L,
      0,
      [:],
      [:],
      "web",
      false,
      PrioritySampling.SAMPLER_KEEP,
      200,
      null)

    TraceMapperV1 mapper = new TraceMapperV1()
    byte[] encoded = serializeMappedPayload(mapper, [[span]])
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded)
    List<String> stringTable = new ArrayList<>()
    stringTable.add("")

    when:
    List<Map<String, Object>> links = readFirstSpanLinks(unpacker, stringTable)

    then:
    assertTrue(links.isEmpty())
  }

  def "test span events are encoded from events tag"() {
    setup:
    List<Map<String, Object>> eventPayload = [
      [
        time_unix_nano: 1234567890L,
        name          : "event.one",
        attributes    : [
          str   : "v",
          int   : 42L,
          double: 12.5d,
          bool  : true,
          arr   : ["x", 7L, 2.5d, false]
        ]
      ],
      [
        time_unix_nano: 1234567891L,
        name          : "event.two"
      ]
    ]
    def span = new TraceGenerator.PojoSpan(
      "service-a",
      "operation-a",
      "resource-a",
      DDTraceId.ONE,
      123L,
      0L,
      1000L,
      2000L,
      0,
      [:],
      ["events": eventPayload],
      "web",
      false,
      PrioritySampling.SAMPLER_KEEP,
      200,
      null)

    TraceMapperV1 mapper = new TraceMapperV1()
    byte[] encoded = serializeMappedPayload(mapper, [[span]])
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded)
    List<String> stringTable = new ArrayList<>()
    stringTable.add("")

    when:
    List<Map<String, Object>> events = readFirstSpanEvents(unpacker, stringTable)

    then:
    assertEquals(2, events.size())
    assertEquals(1234567890L, events[0].timeUnixNano)
    assertEquals("event.one", events[0].name)
    assertEquals("v", events[0].attributes["str"])
    assertEquals(42L, events[0].attributes["int"])
    assertEquals(12.5d, (events[0].attributes["double"] as Number).doubleValue(), 0.000001d)
    assertEquals(true, events[0].attributes["bool"])
    assertEquals(["x", 7L, 2.5d, false], events[0].attributes["arr"])

    assertEquals(1234567891L, events[1].timeUnixNano)
    assertEquals("event.two", events[1].name)
    assertEquals([:], events[1].attributes)
  }

  def "test malformed span events fall back to empty events"() {
    setup:
    def span = new TraceGenerator.PojoSpan(
      "service-a",
      "operation-a",
      "resource-a",
      DDTraceId.ONE,
      123L,
      0L,
      1000L,
      2000L,
      0,
      [:],
      ["events": [foo: "bar"]],
      "web",
      false,
      PrioritySampling.SAMPLER_KEEP,
      200,
      null)

    TraceMapperV1 mapper = new TraceMapperV1()
    byte[] encoded = serializeMappedPayload(mapper, [[span]])
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded)
    List<String> stringTable = new ArrayList<>()
    stringTable.add("")

    when:
    List<Map<String, Object>> events = readFirstSpanEvents(unpacker, stringTable)

    then:
    assertTrue(events.isEmpty())
  }

  def "test meta struct is encoded as bytes attribute"() {
    setup:
    def span = new TraceGenerator.PojoSpan(
      "service-a",
      "operation-a",
      "resource-a",
      DDTraceId.ONE,
      123L,
      0L,
      1000L,
      2000L,
      0,
      [:],
      [:],
      "web",
      false,
      PrioritySampling.SAMPLER_KEEP,
      200,
      null)
    span.setMetaStruct("meta_key", [foo: "bar", answer: 42L])

    TraceMapperV1 mapper = new TraceMapperV1()
    byte[] encoded = serializeMappedPayload(mapper, [[span]])
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded)
    List<String> stringTable = new ArrayList<>()
    stringTable.add("")

    when:
    Map<String, Object> attributes = readFirstSpanAttributes(unpacker, stringTable)
    byte[] metaStructBytes = attributes["meta_key"] as byte[]
    MessageUnpacker metaStructUnpacker = MessagePack.newDefaultUnpacker(metaStructBytes)
    int metaStructFieldCount = metaStructUnpacker.unpackMapHeader()
    Map<String, Object> decodedMetaStruct = [:]
    for (int i = 0; i < metaStructFieldCount; i++) {
      String key = metaStructUnpacker.unpackString()
      switch (metaStructUnpacker.getNextFormat().getValueType()) {
        case org.msgpack.value.ValueType.INTEGER:
          decodedMetaStruct[key] = metaStructUnpacker.unpackLong()
          break
        case org.msgpack.value.ValueType.STRING:
          decodedMetaStruct[key] = metaStructUnpacker.unpackString()
          break
        default:
          Assertions.fail("Unexpected meta_struct value type for key " + key)
      }
    }

    then:
    assertNotNull(metaStructBytes)
    assertEquals("bar", decodedMetaStruct["foo"])
    assertEquals(42L, decodedMetaStruct["answer"])
  }

  def "test map-valued span tags are flattened in v1 attributes"() {
    setup:
    def span = new TraceGenerator.PojoSpan(
      "service-a",
      "operation-a",
      "resource-a",
      DDTraceId.ONE,
      123L,
      0L,
      1000L,
      2000L,
      0,
      [:],
      [
        "usr": [
          "id"           : "123",
          "name"         : "alice",
          "authenticated": true,
          "profile"      : [
            "age": 30L
          ]
        ],
        "appsec.events.users.login.success": [
          "metadata0": [
            "event"   : "login",
            "attempts": 1L
          ],
          "metadata1": [
            "blocked": false
          ]
        ]
      ],
      "web",
      false,
      PrioritySampling.SAMPLER_KEEP,
      0,
      null)

    TraceMapperV1 mapper = new TraceMapperV1()
    byte[] encoded = serializeMappedPayload(mapper, [[span]])
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded)
    List<String> stringTable = new ArrayList<>()
    stringTable.add("")

    when:
    Map<String, Object> attributes = readFirstSpanAttributes(unpacker, stringTable)

    then:
    assertTrue(attributes.containsKey("usr.id"))
    assertTrue(attributes.containsKey("usr.name"))
    assertTrue(attributes.containsKey("usr.authenticated"))
    assertTrue(attributes.containsKey("usr.profile.age"))
    assertTrue(attributes.containsKey("appsec.events.users.login.success.metadata0.event"))
    assertTrue(attributes.containsKey("appsec.events.users.login.success.metadata0.attempts"))
    assertTrue(attributes.containsKey("appsec.events.users.login.success.metadata1.blocked"))

    assertEquals("123", attributes.get("usr.id"))
    assertEquals("alice", attributes.get("usr.name"))
    assertEquals(true, attributes.get("usr.authenticated"))
    assertEquals(30d, (attributes.get("usr.profile.age") as Number).doubleValue(), 0.000001d)
    assertEquals("login", attributes.get("appsec.events.users.login.success.metadata0.event"))
    assertEquals(1d, (attributes.get("appsec.events.users.login.success.metadata0.attempts") as Number).doubleValue(), 0.000001d)
    assertEquals(false, attributes.get("appsec.events.users.login.success.metadata1.blocked"))

    assertTrue(!attributes.containsKey("usr"))
    assertTrue(!attributes.containsKey("appsec.events.users.login.success"))
  }

  def "test primitive span tags are encoded in v1 attributes"() {
    setup:
    def span = new TraceGenerator.PojoSpan(
      "service-a",
      "operation-a",
      "resource-a",
      DDTraceId.ONE,
      123L,
      0L,
      1000L,
      2000L,
      0,
      [:],
      [
        "tag.bool"  : true,
        "tag.int"   : 7,
        "tag.long"  : 9L,
        "tag.float" : 3.5f,
        "tag.double": 4.25d
      ],
      "web",
      false,
      PrioritySampling.SAMPLER_KEEP,
      0,
      null)

    TraceMapperV1 mapper = new TraceMapperV1()
    byte[] encoded = serializeMappedPayload(mapper, [[span]])
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(encoded)
    List<String> stringTable = new ArrayList<>()
    stringTable.add("")

    when:
    Map<String, Object> attributes = readFirstSpanAttributes(unpacker, stringTable)

    then:
    assertEquals(true, attributes.get("tag.bool"))
    assertEquals(7d, (attributes.get("tag.int") as Number).doubleValue(), 0.000001d)
    assertEquals(9d, (attributes.get("tag.long") as Number).doubleValue(), 0.000001d)
    assertEquals(3.5d, (attributes.get("tag.float") as Number).doubleValue(), 0.000001d)
    assertEquals(4.25d, (attributes.get("tag.double") as Number).doubleValue(), 0.000001d)
  }

  private static final class PayloadVerifier implements ByteBufferConsumer, WritableByteChannel {

    private final List<List<TraceGenerator.PojoSpan>> expectedTraces
    private final TraceMapperV1 mapper
    private ByteBuffer captured = ByteBuffer.allocate(200 << 10)
    private int position = 0

    private PayloadVerifier(List<List<TraceGenerator.PojoSpan>> expectedTraces, TraceMapperV1 mapper) {
      this.expectedTraces = expectedTraces
      this.mapper = mapper
    }

    void skipLargeTrace() {
      ++position
    }

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      if (expectedTraces.isEmpty() && messageCount == 0) {
        return
      }
      try {
        Payload payload = mapper.newPayload().withBody(messageCount, buffer)
        payload.writeTo(this)
        captured.flip()

        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(captured)
        if (messageCount == 0) {
          assertEquals(0, unpacker.unpackMapHeader())
          return
        }

        List<String> stringTable = new ArrayList<>()
        stringTable.add("")

        int payloadFieldCount = unpacker.unpackMapHeader()
        assertEquals(10, payloadFieldCount)

        boolean seenChunks = false
        for (int i = 0; i < payloadFieldCount; i++) {
          int fieldId = unpacker.unpackInt()
          if (fieldId == 11) {
            int traceCount = unpacker.unpackArrayHeader()
            assertEquals(messageCount, traceCount)
            seenChunks = true
            for (int traceIndex = 0; traceIndex < traceCount; traceIndex++) {
              List<TraceGenerator.PojoSpan> expectedTrace = expectedTraces.get(position++)
              verifyChunk(unpacker, expectedTrace, stringTable)
            }
          } else {
            skipPayloadField(unpacker, fieldId, stringTable)
          }
        }

        assertTrue(seenChunks)
      } catch (IOException e) {
        Assertions.fail(e.getMessage())
      } finally {
        mapper.reset()
        captured.position(0)
        captured.limit(captured.capacity())
      }
    }

    @Override
    int write(ByteBuffer src) {
      if (captured.remaining() < src.remaining()) {
        ByteBuffer newBuffer = ByteBuffer.allocate(captured.capacity() + src.remaining())
        captured.flip()
        newBuffer.put(captured)
        captured = newBuffer
        return write(src)
      }
      captured.put(src)
      return src.position()
    }

    void verifyTracesConsumed() {
      assertEquals(expectedTraces.size(), position)
    }

    @Override
    boolean isOpen() {
      return true
    }

    @Override
    void close() {
    }
  }

  private static void verifyChunk(
    MessageUnpacker unpacker,
    List<TraceGenerator.PojoSpan> expectedTrace,
    List<String> stringTable) {
    int chunkFieldCount = unpacker.unpackMapHeader()
    assertEquals(6, chunkFieldCount)

    Integer priority = null
    String origin = null
    Map<String, Object> chunkAttributes = null
    byte[] traceId = null
    Integer samplingMechanism = null
    List<TraceGenerator.PojoSpan> decodedSpans = null

    for (int i = 0; i < chunkFieldCount; i++) {
      int fieldId = unpacker.unpackInt()
      switch (fieldId) {
        case 1:
          priority = unpacker.unpackInt()
          break
        case 2:
          origin = readStreamingString(unpacker, stringTable)
          break
        case 3:
          chunkAttributes = readAttributes(unpacker, stringTable)
          break
        case 4:
          decodedSpans = verifySpans(unpacker, expectedTrace, stringTable)
          break
        case 6:
          int traceIdLen = unpacker.unpackBinaryHeader()
          traceId = new byte[traceIdLen]
          unpacker.readPayload(traceId)
          break
        case 7:
          samplingMechanism = unpacker.unpackInt()
          break
        default:
          Assertions.fail("Unexpected chunk field id: " + fieldId)
      }
    }

    assertNotNull(priority)
    assertNotNull(origin)
    assertNotNull(chunkAttributes)
    assertNotNull(decodedSpans)
    assertNotNull(traceId)
    assertNotNull(samplingMechanism)

    TraceGenerator.PojoSpan firstSpan = expectedTrace.get(0)
    assertEquals(firstSpan.samplingPriority(), priority)
    assertEqualsWithNullAsEmpty(firstSpan.getOrigin(), origin)
    assertEquals(1, chunkAttributes.size())
    assertEqualsWithNullAsEmpty(firstSpan.getLocalRootSpan().getServiceName(), chunkAttributes.get("service"))
    assertArrayEquals(traceIdBytes(firstSpan.getTraceId()), traceId)
    assertEquals(expectedSamplingMechanism(firstSpan.getTags()), samplingMechanism)
  }

  private static byte[] traceIdBytes(DDTraceId traceId) {
    ByteBuffer.allocate(16)
      .putLong(traceId.toHighOrderLong())
      .putLong(traceId.toLong())
      .array()
  }

  private static List<TraceGenerator.PojoSpan> verifySpans(
    MessageUnpacker unpacker,
    List<TraceGenerator.PojoSpan> expectedTrace,
    List<String> stringTable) {
    int spanCount = unpacker.unpackArrayHeader()
    assertEquals(expectedTrace.size(), spanCount)

    for (int i = 0; i < spanCount; i++) {
      verifySpan(unpacker, expectedTrace.get(i), stringTable)
    }
    return expectedTrace
  }

  private static void verifySpan(
    MessageUnpacker unpacker,
    TraceGenerator.PojoSpan expectedSpan,
    List<String> stringTable) {
    int spanFieldCount = unpacker.unpackMapHeader()
    assertEquals(16, spanFieldCount)

    String service = null
    String name = null
    String resource = null
    Long spanId = null
    Long parentId = null
    Long start = null
    Long duration = null
    Boolean error = null
    Map<String, Object> attributes = null
    String type = null
    int linksCount = -1
    int eventsCount = -1
    String env = null
    String version = null
    String component = null
    Integer spanKind = null

    for (int i = 0; i < spanFieldCount; i++) {
      int fieldId = unpacker.unpackInt()
      switch (fieldId) {
        case 1:
          service = readStreamingString(unpacker, stringTable)
          break
        case 2:
          name = readStreamingString(unpacker, stringTable)
          break
        case 3:
          resource = readStreamingString(unpacker, stringTable)
          break
        case 4:
          spanId = unpackUnsignedLong(unpacker)
          break
        case 5:
          parentId = unpackUnsignedLong(unpacker)
          break
        case 6:
          start = unpacker.unpackLong()
          break
        case 7:
          duration = unpacker.unpackLong()
          break
        case 8:
          error = unpacker.unpackBoolean()
          break
        case 9:
          attributes = readAttributes(unpacker, stringTable)
          break
        case 10:
          type = readStreamingString(unpacker, stringTable)
          break
        case 11:
          linksCount = unpacker.unpackArrayHeader()
          break
        case 12:
          eventsCount = unpacker.unpackArrayHeader()
          break
        case 13:
          env = readStreamingString(unpacker, stringTable)
          break
        case 14:
          version = readStreamingString(unpacker, stringTable)
          break
        case 15:
          component = readStreamingString(unpacker, stringTable)
          break
        case 16:
          spanKind = unpacker.unpackInt()
          break
        default:
          Assertions.fail("Unexpected span field id: " + fieldId)
      }
    }

    assertEqualsWithNullAsEmpty(expectedSpan.getServiceName(), service)
    assertEqualsWithNullAsEmpty(expectedSpan.getOperationName(), name)
    assertEqualsWithNullAsEmpty(expectedSpan.getResourceName(), resource)
    assertEquals(expectedSpan.getSpanId(), spanId)
    assertEquals(expectedSpan.getParentId(), parentId)
    assertEquals(expectedSpan.getStartTime(), start)
    assertEquals(expectedSpan.getDurationNano(), duration)
    assertEquals(expectedSpan.getError() != 0, error)
    assertEqualsWithNullAsEmpty(expectedSpan.getType(), type)
    assertEquals(0, linksCount)
    assertEquals(0, eventsCount)
    assertEqualsWithNullAsEmpty(expectedSpan.getTag(Tags.ENV), env)
    assertEqualsWithNullAsEmpty(expectedSpan.getTag(Tags.VERSION), version)
    assertEqualsWithNullAsEmpty(expectedSpan.getTag(Tags.COMPONENT), component)
    assertEquals(TraceMapperV1.getSpanKindValue(expectedSpan.getTag(Tags.SPAN_KIND)), spanKind)

    assertNotNull(attributes)
    int expectedHttpStatusCode = expectedSpan.getHttpStatusCode()
    boolean shouldContainHttpStatus = expectedHttpStatusCode != 0 && !expectedSpan.getTags().containsKey("http.status_code")
    Map<String, Object> expectedAttributes = [:]
    for (Map.Entry<String, String> entry : expectedSpan.getBaggage().entrySet()) {
      expectedAttributes.put(entry.getKey(), entry.getValue())
    }
    for (Map.Entry<String, Object> entry : expectedSpan.getTags().entrySet()) {
      if (DDTags.SPAN_EVENTS == entry.getKey()) {
        continue
      }
      addFlattenedExpectedAttribute(expectedAttributes, entry.getKey(), entry.getValue())
    }
    if (shouldContainHttpStatus) {
      expectedAttributes.put("http.status_code", Integer.toString(expectedHttpStatusCode))
    }
    if (expectedSpan.isTopLevel()) {
      expectedAttributes.put(InstrumentationTags.DD_TOP_LEVEL.toString(), 1d)
    }

    assertEquals(expectedAttributes.size(), attributes.size())
    for (Map.Entry<String, Object> entry : expectedAttributes.entrySet()) {
      String key = entry.getKey()
      Object expectedValue = entry.getValue()
      assertTrue(attributes.containsKey(key), "Missing attribute key: $key")
      assertAttributeValueEquals(expectedValue, attributes.get(key), key)
    }
  }

  private static Map<String, Object> readAttributes(MessageUnpacker unpacker, List<String> stringTable) {
    int attrArraySize = unpacker.unpackArrayHeader()
    assertEquals(0, attrArraySize % 3)
    int attrCount = attrArraySize / 3

    Map<String, Object> attributes = new HashMap<>()
    for (int i = 0; i < attrCount; i++) {
      String key = readStreamingString(unpacker, stringTable)
      int attrType = unpacker.unpackInt()
      Object value
      switch (attrType) {
        case TraceMapperV1.VALUE_TYPE_STRING:
          value = readStreamingString(unpacker, stringTable)
          break
        case TraceMapperV1.VALUE_TYPE_BOOLEAN:
          value = unpacker.unpackBoolean()
          break
        case TraceMapperV1.VALUE_TYPE_FLOAT:
          value = unpacker.unpackDouble()
          break
        case TraceMapperV1.VALUE_TYPE_BYTES:
          int len = unpacker.unpackBinaryHeader()
          byte[] data = new byte[len]
          unpacker.readPayload(data)
          value = data
          break
        default:
          Assertions.fail("Unknown attribute value type: " + attrType)
      }
      attributes.put(key, value)
    }
    return attributes
  }

  private static void assertAttributeValueEquals(Object expected, Object actual, String key) {
    if (expected instanceof Number) {
      assertTrue(actual instanceof Number, "Attribute $key should be numeric")
      double expectedValue = ((Number) expected).doubleValue()
      double actualValue = ((Number) actual).doubleValue()
      double delta = Math.max(0.000001d, Math.abs(expectedValue) * 0.000000000001d)
      assertEquals(expectedValue, actualValue, delta, "Numeric mismatch for $key")
    } else if (expected instanceof Boolean) {
      assertEquals(expected, actual, "Boolean mismatch for $key")
    } else {
      assertEquals(String.valueOf(expected), String.valueOf(actual), "String mismatch for $key")
    }
  }

  private static long unpackUnsignedLong(MessageUnpacker unpacker) {
    MessageFormat format = unpacker.nextFormat
    if (format == MessageFormat.UINT64) {
      return DDSpanId.from("${unpacker.unpackBigInteger()}")
    }
    return unpacker.unpackLong()
  }

  private static void addFlattenedExpectedAttribute(
    Map<String, Object> expectedAttributes,
    String key,
    Object value) {
    if (!(value instanceof Map)) {
      expectedAttributes.put(key, value)
      return
    }
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
      addFlattenedExpectedAttribute(
        expectedAttributes,
        key + "." + String.valueOf(entry.getKey()),
        entry.getValue())
    }
  }

  private static int expectedSamplingMechanism(Map<String, Object> tags) {
    Object decisionMakerRaw = tags.get("_dd.p.dm")
    if (decisionMakerRaw == null) {
      return SamplingMechanism.DEFAULT
    }

    String decisionMaker = String.valueOf(decisionMakerRaw)
    try {
      int value = Integer.parseInt(decisionMaker)
      return value < 0 ? -value : value
    } catch (NumberFormatException ignored) {
      int separator = decisionMaker.lastIndexOf('-')
      if (separator >= 0 && separator + 1 < decisionMaker.length()) {
        try {
          int value = Integer.parseInt(decisionMaker.substring(separator + 1))
          return value < 0 ? -value : value
        } catch (NumberFormatException ignoredAgain) {
        }
      }
      return SamplingMechanism.DEFAULT
    }
  }

  private static String readStreamingString(MessageUnpacker unpacker, List<String> stringTable) {
    MessageFormat format = unpacker.getNextFormat()
    if (format == FIXSTR || format == STR8 || format == STR16 || format == STR32) {
      String value = unpacker.unpackString()
      if (!stringTable.contains(value)) {
        stringTable.add(value)
      }
      return value
    }

    int index = unpacker.unpackInt()
    assertTrue(index >= 0 && index < stringTable.size(), "Invalid string-table index: " + index)
    return stringTable.get(index)
  }

  private static void skipPayloadField(MessageUnpacker unpacker, int fieldId, List<String> stringTable) {
    switch (fieldId) {
      case 2:
      case 3:
      case 4:
      case 5:
      case 6:
      case 7:
      case 8:
      case 9:
        readStreamingString(unpacker, stringTable)
        break
      case 10:
        readAttributes(unpacker, stringTable)
        break
      default:
        Assertions.fail("Unexpected payload field id while skipping: " + fieldId)
    }
  }

  private static void skipChunkField(MessageUnpacker unpacker, int fieldId, List<String> stringTable) {
    switch (fieldId) {
      case 1:
        unpacker.unpackInt()
        break
      case 2:
        readStreamingString(unpacker, stringTable)
        break
      case 3:
        readAttributes(unpacker, stringTable)
        break
      case 4:
        int spanCount = unpacker.unpackArrayHeader()
        for (int i = 0; i < spanCount; i++) {
          skipSpan(unpacker, stringTable)
        }
        break
      case 5:
        unpacker.unpackBoolean()
        break
      case 6:
        int len = unpacker.unpackBinaryHeader()
        byte[] ignored = new byte[len]
        unpacker.readPayload(ignored)
        break
      case 7:
        unpacker.unpackInt()
        break
      default:
        Assertions.fail("Unexpected chunk field id while skipping: " + fieldId)
    }
  }

  private static void skipSpan(MessageUnpacker unpacker, List<String> stringTable) {
    int fieldCount = unpacker.unpackMapHeader()
    for (int i = 0; i < fieldCount; i++) {
      int fieldId = unpacker.unpackInt()
      switch (fieldId) {
        case 1:
        case 2:
        case 3:
        case 10:
        case 13:
        case 14:
        case 15:
          readStreamingString(unpacker, stringTable)
          break
        case 4:
        case 5:
          unpacker.unpackValue().asNumberValue().toLong()
          break
        case 6:
        case 7:
          unpacker.unpackLong()
          break
        case 8:
          unpacker.unpackBoolean()
          break
        case 9:
          int attrArraySize = unpacker.unpackArrayHeader()
          int attrCount = attrArraySize / 3
          for (int j = 0; j < attrCount; j++) {
            readStreamingString(unpacker, stringTable)
            int type = unpacker.unpackInt()
            switch (type) {
              case TraceMapperV1.VALUE_TYPE_STRING:
                readStreamingString(unpacker, stringTable)
                break
              case TraceMapperV1.VALUE_TYPE_BOOLEAN:
                unpacker.unpackBoolean()
                break
              case TraceMapperV1.VALUE_TYPE_FLOAT:
                unpacker.unpackDouble()
                break
              case TraceMapperV1.VALUE_TYPE_BYTES:
                int len = unpacker.unpackBinaryHeader()
                byte[] ignored = new byte[len]
                unpacker.readPayload(ignored)
                break
              default:
                Assertions.fail("Unexpected attribute type while skipping: " + type)
            }
          }
          break
        case 11:
        case 12:
          unpacker.unpackArrayHeader()
          break
        case 16:
          unpacker.unpackInt()
          break
        default:
          Assertions.fail("Unexpected span field id while skipping: " + fieldId)
      }
    }
  }

  private static Map<String, Object> readFirstSpanAttributes(
    MessageUnpacker unpacker,
    List<String> stringTable) {
    int payloadFieldCount = unpacker.unpackMapHeader()
    for (int i = 0; i < payloadFieldCount; i++) {
      int payloadFieldId = unpacker.unpackInt()
      if (payloadFieldId != 11) {
        skipPayloadField(unpacker, payloadFieldId, stringTable)
        continue
      }

      int chunkCount = unpacker.unpackArrayHeader()
      assertEquals(1, chunkCount)

      int chunkFieldCount = unpacker.unpackMapHeader()
      for (int chunkFieldIndex = 0; chunkFieldIndex < chunkFieldCount; chunkFieldIndex++) {
        int chunkFieldId = unpacker.unpackInt()
        if (chunkFieldId != 4) {
          skipChunkField(unpacker, chunkFieldId, stringTable)
          continue
        }

        int spanCount = unpacker.unpackArrayHeader()
        assertEquals(1, spanCount)

        int spanFieldCount = unpacker.unpackMapHeader()
        for (int spanFieldIndex = 0; spanFieldIndex < spanFieldCount; spanFieldIndex++) {
          int spanFieldId = unpacker.unpackInt()
          if (spanFieldId == 9) {
            return readAttributes(unpacker, stringTable)
          }
          skipSpanField(unpacker, spanFieldId, stringTable)
        }
      }
    }
    Assertions.fail("Could not find span attributes field in first span")
    return [:]
  }

  private static List<Map<String, Object>> readFirstSpanLinks(
    MessageUnpacker unpacker,
    List<String> stringTable) {
    int payloadFieldCount = unpacker.unpackMapHeader()
    for (int i = 0; i < payloadFieldCount; i++) {
      int payloadFieldId = unpacker.unpackInt()
      if (payloadFieldId != 11) {
        skipPayloadField(unpacker, payloadFieldId, stringTable)
        continue
      }

      int chunkCount = unpacker.unpackArrayHeader()
      assertEquals(1, chunkCount)

      int chunkFieldCount = unpacker.unpackMapHeader()
      for (int chunkFieldIndex = 0; chunkFieldIndex < chunkFieldCount; chunkFieldIndex++) {
        int chunkFieldId = unpacker.unpackInt()
        if (chunkFieldId != 4) {
          skipChunkField(unpacker, chunkFieldId, stringTable)
          continue
        }

        int spanCount = unpacker.unpackArrayHeader()
        assertEquals(1, spanCount)

        int spanFieldCount = unpacker.unpackMapHeader()
        for (int spanFieldIndex = 0; spanFieldIndex < spanFieldCount; spanFieldIndex++) {
          int spanFieldId = unpacker.unpackInt()
          if (spanFieldId == 11) {
            return readSpanLinks(unpacker, stringTable)
          }
          skipSpanField(unpacker, spanFieldId, stringTable)
        }
      }
    }
    Assertions.fail("Could not find span links field in first span")
    return []
  }

  private static void skipSpanField(MessageUnpacker unpacker, int fieldId, List<String> stringTable) {
    switch (fieldId) {
      case 1:
      case 2:
      case 3:
      case 10:
      case 13:
      case 14:
      case 15:
        readStreamingString(unpacker, stringTable)
        break
      case 4:
      case 5:
        unpacker.unpackValue().asNumberValue().toLong()
        break
      case 6:
      case 7:
        unpacker.unpackLong()
        break
      case 8:
        unpacker.unpackBoolean()
        break
      case 9:
        readAttributes(unpacker, stringTable)
        break
      case 12:
        int eventsCount = unpacker.unpackArrayHeader()
        for (int j = 0; j < eventsCount; j++) {
          skipSpanEvent(unpacker, stringTable)
        }
        break
      case 11:
        int linksCount = unpacker.unpackArrayHeader()
        for (int j = 0; j < linksCount; j++) {
          int linkFieldCount = unpacker.unpackMapHeader()
          for (int k = 0; k < linkFieldCount; k++) {
            int linkFieldId = unpacker.unpackInt()
            switch (linkFieldId) {
              case 1:
                int traceIdLen = unpacker.unpackBinaryHeader()
                byte[] ignored = new byte[traceIdLen]
                unpacker.readPayload(ignored)
                break
              case 2:
              case 5:
                unpacker.unpackValue().asNumberValue().toLong()
                break
              case 3:
                readAttributes(unpacker, stringTable)
                break
              case 4:
                readStreamingString(unpacker, stringTable)
                break
              default:
                Assertions.fail("Unexpected span link field id while skipping: " + linkFieldId)
            }
          }
        }
        break
      case 16:
        unpacker.unpackInt()
        break
      default:
        Assertions.fail("Unexpected span field id while skipping: " + fieldId)
    }
  }

  private static List<Map<String, Object>> readSpanLinks(
    MessageUnpacker unpacker,
    List<String> stringTable) {
    int linksCount = unpacker.unpackArrayHeader()
    List<Map<String, Object>> links = []

    for (int i = 0; i < linksCount; i++) {
      int linkFieldCount = unpacker.unpackMapHeader()
      assertEquals(5, linkFieldCount)

      byte[] traceId = null
      Long spanId = null
      Map<String, Object> attributes = null
      String tracestate = null
      Long flags = null

      for (int j = 0; j < linkFieldCount; j++) {
        int linkFieldId = unpacker.unpackInt()
        switch (linkFieldId) {
          case 1:
            int traceIdLen = unpacker.unpackBinaryHeader()
            traceId = new byte[traceIdLen]
            unpacker.readPayload(traceId)
            break
          case 2:
            spanId = unpacker.unpackValue().asNumberValue().toLong()
            break
          case 3:
            attributes = readAttributes(unpacker, stringTable)
            break
          case 4:
            tracestate = readStreamingString(unpacker, stringTable)
            break
          case 5:
            flags = unpacker.unpackValue().asNumberValue().toLong()
            break
          default:
            Assertions.fail("Unexpected span link field id: " + linkFieldId)
        }
      }

      links.add([
        traceId   : traceId,
        spanId    : spanId,
        attributes: attributes,
        tracestate: tracestate,
        flags     : flags
      ])
    }

    return links
  }

  private static List<Map<String, Object>> readFirstSpanEvents(
    MessageUnpacker unpacker,
    List<String> stringTable) {
    int payloadFieldCount = unpacker.unpackMapHeader()
    for (int i = 0; i < payloadFieldCount; i++) {
      int payloadFieldId = unpacker.unpackInt()
      if (payloadFieldId != 11) {
        skipPayloadField(unpacker, payloadFieldId, stringTable)
        continue
      }

      int chunkCount = unpacker.unpackArrayHeader()
      assertEquals(1, chunkCount)

      int chunkFieldCount = unpacker.unpackMapHeader()
      for (int chunkFieldIndex = 0; chunkFieldIndex < chunkFieldCount; chunkFieldIndex++) {
        int chunkFieldId = unpacker.unpackInt()
        if (chunkFieldId != 4) {
          skipChunkField(unpacker, chunkFieldId, stringTable)
          continue
        }

        int spanCount = unpacker.unpackArrayHeader()
        assertEquals(1, spanCount)

        int spanFieldCount = unpacker.unpackMapHeader()
        for (int spanFieldIndex = 0; spanFieldIndex < spanFieldCount; spanFieldIndex++) {
          int spanFieldId = unpacker.unpackInt()
          if (spanFieldId == 12) {
            return readSpanEvents(unpacker, stringTable)
          }
          skipSpanField(unpacker, spanFieldId, stringTable)
        }
      }
    }
    Assertions.fail("Could not find span events field in first span")
    return []
  }

  private static List<Map<String, Object>> readSpanEvents(
    MessageUnpacker unpacker,
    List<String> stringTable) {
    int eventsCount = unpacker.unpackArrayHeader()
    List<Map<String, Object>> events = []

    for (int i = 0; i < eventsCount; i++) {
      int eventFieldCount = unpacker.unpackMapHeader()
      assertEquals(3, eventFieldCount)

      Long timeUnixNano = null
      String name = null
      Map<String, Object> attributes = null

      for (int j = 0; j < eventFieldCount; j++) {
        int eventFieldId = unpacker.unpackInt()
        switch (eventFieldId) {
          case 1:
            timeUnixNano = unpacker.unpackLong()
            break
          case 2:
            name = readStreamingString(unpacker, stringTable)
            break
          case 3:
            attributes = readEventAttributes(unpacker, stringTable)
            break
          default:
            Assertions.fail("Unexpected span event field id: " + eventFieldId)
        }
      }

      events.add([
        timeUnixNano: timeUnixNano,
        name        : name,
        attributes  : attributes
      ])
    }
    return events
  }

  private static Map<String, Object> readEventAttributes(
    MessageUnpacker unpacker,
    List<String> stringTable) {
    int attrArraySize = unpacker.unpackArrayHeader()
    assertEquals(0, attrArraySize % 3)
    int attrCount = attrArraySize / 3
    Map<String, Object> attributes = new HashMap<>()

    for (int i = 0; i < attrCount; i++) {
      String key = readStreamingString(unpacker, stringTable)
      int attrType = unpacker.unpackInt()
      Object value
      switch (attrType) {
        case TraceMapperV1.VALUE_TYPE_STRING:
          value = readStreamingString(unpacker, stringTable)
          break
        case TraceMapperV1.VALUE_TYPE_BOOLEAN:
          value = unpacker.unpackBoolean()
          break
        case TraceMapperV1.VALUE_TYPE_FLOAT:
          value = unpacker.unpackDouble()
          break
        case TraceMapperV1.VALUE_TYPE_INT:
          value = unpacker.unpackLong()
          break
        case TraceMapperV1.VALUE_TYPE_ARRAY:
          value = readEventArrayValue(unpacker, stringTable)
          break
        default:
          Assertions.fail("Unknown event attribute value type: " + attrType)
      }
      attributes.put(key, value)
    }
    return attributes
  }

  private static List<Object> readEventArrayValue(MessageUnpacker unpacker, List<String> stringTable) {
    int itemArraySize = unpacker.unpackArrayHeader()
    assertEquals(0, itemArraySize % 2)
    int itemCount = itemArraySize / 2
    List<Object> values = []
    for (int i = 0; i < itemCount; i++) {
      int itemType = unpacker.unpackInt()
      switch (itemType) {
        case TraceMapperV1.VALUE_TYPE_STRING:
          values.add(readStreamingString(unpacker, stringTable))
          break
        case TraceMapperV1.VALUE_TYPE_BOOLEAN:
          values.add(unpacker.unpackBoolean())
          break
        case TraceMapperV1.VALUE_TYPE_FLOAT:
          values.add(unpacker.unpackDouble())
          break
        case TraceMapperV1.VALUE_TYPE_INT:
          values.add(unpacker.unpackLong())
          break
        default:
          Assertions.fail("Unknown event array item type: " + itemType)
      }
    }
    return values
  }

  private static void skipSpanEvent(MessageUnpacker unpacker, List<String> stringTable) {
    int fieldCount = unpacker.unpackMapHeader()
    for (int i = 0; i < fieldCount; i++) {
      int fieldId = unpacker.unpackInt()
      switch (fieldId) {
        case 1:
          unpacker.unpackLong()
          break
        case 2:
          readStreamingString(unpacker, stringTable)
          break
        case 3:
          readEventAttributes(unpacker, stringTable)
          break
        default:
          Assertions.fail("Unexpected event field id while skipping: " + fieldId)
      }
    }
  }

  private static byte[] serializeMappedPayload(
    TraceMapperV1 mapper,
    List<List<TraceGenerator.PojoSpan>> traces) {
    CapturedBody capturedBody = new CapturedBody(mapper)
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(2 << 20, capturedBody))

    for (List<TraceGenerator.PojoSpan> trace : traces) {
      assertTrue(packer.format(trace, mapper))
    }
    packer.flush()

    assertNotNull(capturedBody.payloadBytes)
    return capturedBody.payloadBytes
  }

  private static byte[] serializePayload(Payload payload) {
    ByteArrayChannel channel = new ByteArrayChannel()
    payload.writeTo(channel)
    return channel.bytes()
  }

  private static class CapturedBody implements ByteBufferConsumer {
    private final TraceMapperV1 mapper
    private byte[] payloadBytes

    private CapturedBody(TraceMapperV1 mapper) {
      this.mapper = mapper
    }

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      Payload payload = mapper.newPayload().withBody(messageCount, buffer)
      payloadBytes = serializePayload(payload)
      mapper.reset()
    }
  }

  private static class CountingPojoSpan extends TraceGenerator.PojoSpan {
    int processTagsAndBaggageCount = 0

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
      origin)
    }

    @Override
    void processTagsAndBaggage(MetadataConsumer consumer) {
      processTagsAndBaggageCount++
      super.processTagsAndBaggage(consumer)
    }

    @Override
    void processTagsAndBaggage(MetadataConsumer consumer, boolean injectLinksAsTags, boolean injectBaggageAsTags) {
      processTagsAndBaggageCount++
      super.processTagsAndBaggage(consumer, injectLinksAsTags, injectBaggageAsTags)
    }
  }

  private static class ByteArrayChannel implements WritableByteChannel {
    private byte[] data = new byte[0]

    @Override
    int write(ByteBuffer src) {
      int len = src.remaining()
      byte[] incoming = new byte[len]
      src.get(incoming)
      byte[] combined = new byte[data.length + incoming.length]
      System.arraycopy(data, 0, combined, 0, data.length)
      System.arraycopy(incoming, 0, combined, data.length, incoming.length)
      data = combined
      return len
    }

    byte[] bytes() {
      return data
    }

    @Override
    boolean isOpen() {
      return true
    }

    @Override
    void close() {
    }
  }

  private static void assertEqualsWithNullAsEmpty(CharSequence expected, CharSequence actual) {
    if (expected == null) {
      assertEquals("", actual)
    } else {
      assertEquals(expected.toString(), actual.toString())
    }
  }
}
