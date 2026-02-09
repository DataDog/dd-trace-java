package datadog.trace.common.writer.ddagent

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED
import static datadog.trace.common.writer.TraceGenerator.generateRandomTraces
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.msgpack.core.MessageFormat.FIXSTR
import static org.msgpack.core.MessageFormat.FLOAT32
import static org.msgpack.core.MessageFormat.FLOAT64
import static org.msgpack.core.MessageFormat.INT16
import static org.msgpack.core.MessageFormat.INT32
import static org.msgpack.core.MessageFormat.INT64
import static org.msgpack.core.MessageFormat.INT8
import static org.msgpack.core.MessageFormat.NEGFIXINT
import static org.msgpack.core.MessageFormat.POSFIXINT
import static org.msgpack.core.MessageFormat.STR16
import static org.msgpack.core.MessageFormat.STR32
import static org.msgpack.core.MessageFormat.STR8
import static org.msgpack.core.MessageFormat.UINT16
import static org.msgpack.core.MessageFormat.UINT32
import static org.msgpack.core.MessageFormat.UINT64
import static org.msgpack.core.MessageFormat.UINT8

import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.api.Config
import datadog.trace.api.DDTags
import datadog.trace.api.DDTraceId
import datadog.trace.api.ProcessTags
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.writer.Payload
import datadog.trace.common.writer.TraceGenerator
import datadog.trace.test.util.DDSpecification
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import org.junit.Assert
import org.msgpack.core.MessageFormat
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker

/**
 * Test class for TraceMapperV1 payload format
 */
class TraceMapperV1PayloadTest extends DDSpecification {

  def "test traces written correctly"() {
    setup:
    List<List<TraceGenerator.PojoSpan>> traces = generateRandomTraces(traceCount, lowCardinality)
    TraceMapperV1 traceMapper = new TraceMapperV1()
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper)

    def buffer = new FlushingBuffer(bufferSize, verifier)
    MsgPackWriter packer = new MsgPackWriter(buffer)

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
    30 << 10   | 1          | true
    30 << 10   | 2          | true
    20 << 10   | 0          | false
    20 << 10   | 1          | false
    30 << 10   | 1          | false
    30 << 10   | 2          | false
    100 << 10  | 0          | true
    100 << 10  | 1          | true
    100 << 10  | 10         | true
    100 << 10  | 100        | true
    100 << 10  | 0          | false
    100 << 10  | 1          | false
    100 << 10  | 10         | false
    100 << 10  | 100        | false
  }

  def "test endpoint returns v1.0"() {
    setup:
    TraceMapperV1 traceMapper = new TraceMapperV1()

    expect:
    traceMapper.endpoint() == "v1.0"
  }

  def "test span kind value conversion"() {
    expect:
    TraceMapperV1.getSpanKindValue(null) == TraceMapperV1.SPAN_KIND_INTERNAL
    TraceMapperV1.getSpanKindValue(Tags.SPAN_KIND_INTERNAL) == TraceMapperV1.SPAN_KIND_INTERNAL
    TraceMapperV1.getSpanKindValue(Tags.SPAN_KIND_SERVER) == TraceMapperV1.SPAN_KIND_SERVER
    TraceMapperV1.getSpanKindValue(Tags.SPAN_KIND_CLIENT) == TraceMapperV1.SPAN_KIND_CLIENT
    TraceMapperV1.getSpanKindValue(Tags.SPAN_KIND_PRODUCER) == TraceMapperV1.SPAN_KIND_PRODUCER
    TraceMapperV1.getSpanKindValue(Tags.SPAN_KIND_CONSUMER) == TraceMapperV1.SPAN_KIND_CONSUMER
    TraceMapperV1.getSpanKindValue("unknown") == TraceMapperV1.SPAN_KIND_INTERNAL
  }

  def "test string table deduplication"() {
    setup:
    TraceMapperV1.StringTable stringTable = new TraceMapperV1.StringTable()

    expect:
    // Empty string should be pre-populated at index 0
    stringTable.get("") == 0

    // New strings should return null until added
    stringTable.get("test") == null

    // After adding, should return the index
    when:
    stringTable.add("test")
    then:
    stringTable.get("test") == 1

    // Adding same string again shouldn't change index
    when:
    stringTable.add("test")
    then:
    stringTable.get("test") == 1

    // New string gets next index
    when:
    stringTable.add("another")
    then:
    stringTable.get("another") == 2

    // Clear should reset
    when:
    stringTable.clear()
    then:
    stringTable.get("") == 0
    stringTable.get("test") == null
    stringTable.size() == 1
  }

  def "test simple span serialization"() {
    setup:
    def span = new TraceGenerator.PojoSpan(
      "test-service",
      "test-operation",
      "test-resource",
      DDTraceId.ONE,
      123L,
      456L,
      1000000L,
      5000L,
      0,
      [:],
      [:],
      "web",
      false,
      PrioritySampling.SAMPLER_KEEP,
      200,
      "test-origin")
    def traces = [[span]]
    TraceMapperV1 traceMapper = new TraceMapperV1()
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper)
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier))

    when:
    packer.format([span], traceMapper)
    packer.flush()

    then:
    verifier.verifyTracesConsumed()
  }

  def "test error span serialization"() {
    setup:
    def span = new TraceGenerator.PojoSpan(
      "test-service",
      "test-operation",
      "test-resource",
      DDTraceId.ONE,
      123L,
      456L,
      1000000L,
      5000L,
      1,  // error = 1
      [:],
      [:],
      "web",
      false,
      PrioritySampling.SAMPLER_KEEP,
      500,
      null)
    def traces = [[span]]
    TraceMapperV1 traceMapper = new TraceMapperV1()
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper)
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier))

    when:
    packer.format([span], traceMapper)
    packer.flush()

    then:
    verifier.verifyTracesConsumed()
  }

  def "test span with promoted tags"() {
    setup:
    def tags = [
      (Tags.ENV): "production",
      (Tags.DD_VERSION): "1.0.0",
      (Tags.COMPONENT): "http-client",
      (Tags.SPAN_KIND): Tags.SPAN_KIND_CLIENT,
      "custom.tag": "custom-value"
    ]
    def span = new TraceGenerator.PojoSpan(
      "test-service",
      "test-operation",
      "test-resource",
      DDTraceId.ONE,
      123L,
      456L,
      1000000L,
      5000L,
      0,
      [:],
      tags,
      "http",
      false,
      PrioritySampling.SAMPLER_KEEP,
      200,
      null)
    def traces = [[span]]
    TraceMapperV1 traceMapper = new TraceMapperV1()
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper)
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier))

    when:
    packer.format([span], traceMapper)
    packer.flush()

    then:
    verifier.verifyTracesConsumed()
  }

  def "test span with metrics"() {
    setup:
    def tags = [
      "metric.int": 42,
      "metric.long": 1234567890L,
      "metric.float": 3.14f,
      "metric.double": 2.718281828d
    ]
    def span = new TraceGenerator.PojoSpan(
      "test-service",
      "test-operation",
      "test-resource",
      DDTraceId.ONE,
      123L,
      456L,
      1000000L,
      5000L,
      0,
      [:],
      tags,
      "web",
      false,
      PrioritySampling.SAMPLER_KEEP,
      0,
      null)
    def traces = [[span]]
    TraceMapperV1 traceMapper = new TraceMapperV1()
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper)
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier))

    when:
    packer.format([span], traceMapper)
    packer.flush()

    then:
    verifier.verifyTracesConsumed()
  }

  def "test process tags serialization"() {
    setup:
    assertNotNull(ProcessTags.tagsForSerialization)
    def spans = (1..2).collect {
      new TraceGenerator.PojoSpan(
        'service',
        'operation',
        'resource',
        DDTraceId.ONE,
        it,
        -1L,
        123L,
        456L,
        0,
        [:],
        [:],
        'type',
        false,
        0,
        0,
        'origin')
    }

    def traces = [spans]
    TraceMapperV1 traceMapper = new TraceMapperV1()
    PayloadVerifier verifier = new PayloadVerifier(traces, traceMapper)
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier))

    when:
    packer.format(spans, traceMapper)
    packer.flush()

    then:
    verifier.verifyTracesConsumed()
  }

  def "test mapper reset clears string table"() {
    setup:
    TraceMapperV1 traceMapper = new TraceMapperV1()
    def span = new TraceGenerator.PojoSpan(
      "test-service",
      "test-operation",
      "test-resource",
      DDTraceId.ONE,
      123L,
      456L,
      1000000L,
      5000L,
      0,
      [:],
      [:],
      "web",
      false,
      PrioritySampling.UNSET,
      0,
      null)
    def verifier = new PayloadVerifier([[span]], traceMapper)
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(20 << 10, verifier))

    when:
    packer.format([span], traceMapper)
    packer.flush()
    traceMapper.reset()

    then:
    // String table should be cleared (only empty string at index 0)
    traceMapper.stringTable.size() == 1
    traceMapper.stringTable.get("") == 0
    traceMapper.stringTable.get("test-service") == null
  }

  /**
   * PayloadVerifier for V1.0 format
   * 
   * The V1.0 format uses:
   * - Integer field IDs instead of string keys
   * - Attributes encoded as arrays with triplets (key, type, value)
   * - Promoted fields (env, version, component, spanKind) as separate span fields
   * - Error as boolean instead of int
   * - SpanKind as uint32 matching OTEL spec values
   */
  private static final class PayloadVerifier implements ByteBufferConsumer, WritableByteChannel {

    private final List<List<TraceGenerator.PojoSpan>> expectedTraces
    private final TraceMapperV1 mapper
    private ByteBuffer captured = ByteBuffer.allocate(200 << 10)
    private int position = 0
    // String table for streaming string decoding
    private List<String> stringTable = new ArrayList<>()

    private PayloadVerifier(List<List<TraceGenerator.PojoSpan>> traces, TraceMapperV1 mapper) {
      this.expectedTraces = traces
      this.mapper = mapper
    }

    private void resetStringTable() {
      stringTable.clear()
      stringTable.add("") // Index 0 is always empty string
    }

    void skipLargeTrace() {
      ++position
    }

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      if (expectedTraces.isEmpty() && messageCount == 0) {
        return
      }
      int processTagsCount = 0
      resetStringTable() // Reset string table for each payload
      try {
        Payload payload = mapper.newPayload().withBody(messageCount, buffer)
        payload.writeTo(this)
        captured.flip()
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(captured)

        // V1.0 format: map header with field 11 (chunks) containing array of trace chunks
        int mapSize = unpacker.unpackMapHeader()
        assertTrue(mapSize >= 1, "Expected at least 1 field in payload map")

        // Read field ID (should be 11 for chunks)
        int fieldId = unpacker.unpackInt()
        assertEquals(TraceMapperV1.FIELD_CHUNKS, fieldId, "Expected chunks field ID")

        // Read trace count
        int traceCount = unpacker.unpackArrayHeader()

        for (int i = 0; i < traceCount; ++i) {
          List<TraceGenerator.PojoSpan> expectedTrace = expectedTraces.get(position++)
          int spanCount = unpacker.unpackArrayHeader()
          assertEquals(expectedTrace.size(), spanCount)

          for (int k = 0; k < spanCount; ++k) {
            TraceGenerator.PojoSpan expectedSpan = expectedTrace.get(k)

            // V1.0 spans are maps with integer field IDs
            int spanFieldCount = unpacker.unpackMapHeader()
            assertEquals(16, spanFieldCount, "Expected 16 fields in span")

            Map<Integer, Object> spanFields = new HashMap<>()
            Map<String, Object> attributes = new HashMap<>()

            for (int f = 0; f < spanFieldCount; ++f) {
              int spanFieldId = unpacker.unpackInt()

              switch (spanFieldId) {
                case TraceMapperV1.SPAN_FIELD_SERVICE:
                  spanFields.put(spanFieldId, readStreamingString(unpacker))
                  break
                case TraceMapperV1.SPAN_FIELD_NAME:
                  spanFields.put(spanFieldId, readStreamingString(unpacker))
                  break
                case TraceMapperV1.SPAN_FIELD_RESOURCE:
                  spanFields.put(spanFieldId, readStreamingString(unpacker))
                  break
                case TraceMapperV1.SPAN_FIELD_SPAN_ID:
                  spanFields.put(spanFieldId, unpacker.unpackValue().asNumberValue().toLong())
                  break
                case TraceMapperV1.SPAN_FIELD_PARENT_ID:
                  spanFields.put(spanFieldId, unpacker.unpackValue().asNumberValue().toLong())
                  break
                case TraceMapperV1.SPAN_FIELD_START:
                  spanFields.put(spanFieldId, unpacker.unpackLong())
                  break
                case TraceMapperV1.SPAN_FIELD_DURATION:
                  spanFields.put(spanFieldId, unpacker.unpackLong())
                  break
                case TraceMapperV1.SPAN_FIELD_ERROR:
                  spanFields.put(spanFieldId, unpacker.unpackBoolean())
                  break
                case TraceMapperV1.SPAN_FIELD_ATTRIBUTES:
                // Attributes are encoded as array with triplets: key, type, value
                  int attrArraySize = unpacker.unpackArrayHeader()
                  int attrCount = attrArraySize / 3
                  for (int a = 0; a < attrCount; ++a) {
                    String attrKey = readStreamingString(unpacker)
                    int attrType = unpacker.unpackInt()
                    Object attrValue = readAttributeValue(unpacker, attrType)
                    attributes.put(attrKey, attrValue)
                  }
                  spanFields.put(spanFieldId, attributes)
                  break
                case TraceMapperV1.SPAN_FIELD_TYPE:
                  spanFields.put(spanFieldId, readStreamingString(unpacker))
                  break
                case TraceMapperV1.SPAN_FIELD_SPAN_LINKS:
                  int linksCount = unpacker.unpackArrayHeader()
                // Skip span links for now
                  spanFields.put(spanFieldId, linksCount)
                  break
                case TraceMapperV1.SPAN_FIELD_SPAN_EVENTS:
                  int eventsCount = unpacker.unpackArrayHeader()
                // Skip span events for now
                  spanFields.put(spanFieldId, eventsCount)
                  break
                case TraceMapperV1.SPAN_FIELD_ENV:
                  spanFields.put(spanFieldId, readStreamingString(unpacker))
                  break
                case TraceMapperV1.SPAN_FIELD_VERSION:
                  spanFields.put(spanFieldId, readStreamingString(unpacker))
                  break
                case TraceMapperV1.SPAN_FIELD_COMPONENT:
                  spanFields.put(spanFieldId, readStreamingString(unpacker))
                  break
                case TraceMapperV1.SPAN_FIELD_SPAN_KIND:
                  spanFields.put(spanFieldId, unpacker.unpackInt())
                  break
                default:
                  Assert.fail("Unknown span field ID: " + spanFieldId)
              }
            }

            // Verify basic span fields
            assertEqualsWithNullAsEmpty(expectedSpan.getServiceName(),
              (String) spanFields.get(TraceMapperV1.SPAN_FIELD_SERVICE))
            assertEqualsWithNullAsEmpty(expectedSpan.getOperationName(),
              (String) spanFields.get(TraceMapperV1.SPAN_FIELD_NAME))
            assertEqualsWithNullAsEmpty(expectedSpan.getResourceName(),
              (String) spanFields.get(TraceMapperV1.SPAN_FIELD_RESOURCE))
            assertEquals(expectedSpan.getSpanId(),
              spanFields.get(TraceMapperV1.SPAN_FIELD_SPAN_ID))
            assertEquals(expectedSpan.getParentId(),
              spanFields.get(TraceMapperV1.SPAN_FIELD_PARENT_ID))
            assertEquals(expectedSpan.getStartTime(),
              spanFields.get(TraceMapperV1.SPAN_FIELD_START))
            assertEquals(expectedSpan.getDurationNano(),
              spanFields.get(TraceMapperV1.SPAN_FIELD_DURATION))

            // V1.0 format: error is boolean
            boolean expectedError = expectedSpan.getError() != 0
            assertEquals(expectedError, spanFields.get(TraceMapperV1.SPAN_FIELD_ERROR))

            assertEqualsWithNullAsEmpty(expectedSpan.getType(),
              (String) spanFields.get(TraceMapperV1.SPAN_FIELD_TYPE))

            // Verify promoted fields
            String expectedEnv = expectedSpan.getTag(Tags.ENV)
            String actualEnv = (String) spanFields.get(TraceMapperV1.SPAN_FIELD_ENV)
            if (expectedEnv != null) {
              assertEquals(expectedEnv, actualEnv)
            }

            String expectedVersion = expectedSpan.getTag(Tags.DD_VERSION)
            String actualVersion = (String) spanFields.get(TraceMapperV1.SPAN_FIELD_VERSION)
            if (expectedVersion != null) {
              assertEquals(expectedVersion, actualVersion)
            }

            String expectedComponent = expectedSpan.getTag(Tags.COMPONENT)
            String actualComponent = (String) spanFields.get(TraceMapperV1.SPAN_FIELD_COMPONENT)
            if (expectedComponent != null) {
              assertEquals(expectedComponent, actualComponent)
            }

            // Verify span kind is OTEL uint32
            int spanKind = (int) spanFields.get(TraceMapperV1.SPAN_FIELD_SPAN_KIND)
            String expectedSpanKind = expectedSpan.getTag(Tags.SPAN_KIND)
            assertEquals(TraceMapperV1.getSpanKindValue(expectedSpanKind), spanKind)

            // Verify attributes
            Map<String, Object> spanAttributes = (Map<String, Object>) spanFields.get(TraceMapperV1.SPAN_FIELD_ATTRIBUTES)

            // Check process tags (only on first span)
            if (spanAttributes.containsKey(DDTags.PROCESS_TAGS)) {
              processTagsCount++
              assertTrue(Config.get().isExperimentalPropagateProcessTagsEnabled())
              assertEquals(0, k)
              assertEquals(ProcessTags.tagsForSerialization.toString(), spanAttributes.get(DDTags.PROCESS_TAGS))
            }

            // Verify sampling priority attribute (on first and last span)
            if (spanAttributes.containsKey("_sampling_priority_v1")) {
              if (k == 0 || k == spanCount - 1) {
                Number priority = (Number) spanAttributes.get("_sampling_priority_v1")
                assertEquals(expectedSpan.samplingPriority(), priority.intValue())
              } else {
                assertFalse(expectedSpan.hasSamplingPriority())
              }
            }

            // Verify measured attribute
            if (expectedSpan.isMeasured()) {
              assertTrue(spanAttributes.containsKey(DD_MEASURED.toString()))
              assertEquals(1, ((Number) spanAttributes.get(DD_MEASURED.toString())).intValue())
            }

            // Verify other tags/metrics are in attributes
            for (Map.Entry<String, Object> tagEntry : expectedSpan.getTags().entrySet()) {
              String tagKey = tagEntry.getKey()
              Object tagValue = tagEntry.getValue()

              // Skip promoted tags
              if (Tags.ENV.equals(tagKey) || Tags.DD_VERSION.equals(tagKey) ||
                Tags.COMPONENT.equals(tagKey) || Tags.SPAN_KIND.equals(tagKey)) {
                continue
              }

              if (spanAttributes.containsKey(tagKey)) {
                Object actualValue = spanAttributes.get(tagKey)
                if (tagValue instanceof Number && actualValue instanceof Number) {
                  assertEquals(((Number) tagValue).doubleValue(),
                    ((Number) actualValue).doubleValue(), 0.001, tagKey)
                } else {
                  assertEquals(String.valueOf(tagValue), String.valueOf(actualValue), tagKey)
                }
              }
            }
          }
        }
      } catch (IOException e) {
        Assert.fail(e.getMessage())
      } finally {
        mapper.reset()
        captured.position(0)
        captured.limit(captured.capacity())
        assert processTagsCount == 0 || Config.get().isExperimentalPropagateProcessTagsEnabled()
      }
    }

    private String readStreamingString(MessageUnpacker unpacker) throws IOException {
      MessageFormat format = unpacker.getNextFormat()
      if (format == FIXSTR || format == STR8 || format == STR16 || format == STR32) {
        String str = unpacker.unpackString()
        // Add to string table if not already present
        if (!stringTable.contains(str)) {
          stringTable.add(str)
        }
        return str
      }
      // It's an index reference (positive integer)
      int index = unpacker.unpackInt()
      if (index < stringTable.size()) {
        return stringTable.get(index)
      }
      // Return placeholder if index is out of bounds (shouldn't happen in valid payloads)
      return "[string_ref:" + index + "]"
    }

    private Object readAttributeValue(MessageUnpacker unpacker, int attrType) throws IOException {
      switch (attrType) {
        case TraceMapperV1.STRING_VALUE_TYPE:
          return readStreamingString(unpacker)
        case TraceMapperV1.BOOL_VALUE_TYPE:
          return unpacker.unpackBoolean()
        case TraceMapperV1.FLOAT_VALUE_TYPE:
        // Float values can be encoded as integer or double depending on the actual value
          MessageFormat format = unpacker.getNextFormat()
          switch (format) {
            case FLOAT32:
              return (double) unpacker.unpackFloat()
            case FLOAT64:
              return unpacker.unpackDouble()
            case NEGFIXINT:
            case POSFIXINT:
            case INT8:
            case UINT8:
            case INT16:
            case UINT16:
            case INT32:
            case UINT32:
              return (double) unpacker.unpackInt()
            case INT64:
            case UINT64:
              return (double) unpacker.unpackLong()
            default:
              Assert.fail("Unexpected format for FLOAT_VALUE_TYPE: " + format)
              return null
          }
        case TraceMapperV1.INT_VALUE_TYPE:
          return unpacker.unpackLong()
        case TraceMapperV1.BYTES_VALUE_TYPE:
          int len = unpacker.unpackBinaryHeader()
          byte[] bytes = new byte[len]
          unpacker.readPayload(bytes)
          return bytes
        case TraceMapperV1.ARRAY_VALUE_TYPE:
          int arrayLen = unpacker.unpackArrayHeader()
          List<Object> array = new ArrayList<>()
          for (int i = 0; i < arrayLen; ++i) {
            int innerType = unpacker.unpackInt()
            array.add(readAttributeValue(unpacker, innerType))
          }
          return array
        default:
          Assert.fail("Unknown attribute type: " + attrType)
          return null
      }
    }

    @Override
    int write(ByteBuffer src) {
      if (captured.remaining() < src.remaining()) {
        ByteBuffer newBuffer = ByteBuffer.allocate(captured.capacity() + src.capacity())
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

  private static void assertEqualsWithNullAsEmpty(CharSequence expected, CharSequence actual) {
    if (null == expected) {
      assertEquals("", actual)
    } else {
      assertEquals(expected.toString(), actual.toString())
    }
  }
}

