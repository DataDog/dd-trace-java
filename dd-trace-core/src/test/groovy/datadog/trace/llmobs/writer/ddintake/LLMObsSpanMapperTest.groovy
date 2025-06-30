package datadog.trace.llmobs.writer.ddintake

import datadog.trace.common.writer.ListWriter
import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.api.llmobs.LLMObsTags
import datadog.trace.api.DDTags
import datadog.trace.core.DDSpan
import datadog.trace.core.test.DDCoreSpecification
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker

import java.nio.ByteBuffer

class LLMObsSpanMapperTest extends DDCoreSpecification {

  def "test LLMObs span mapper"() {
    setup:
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    DDSpan span = (DDSpan) tracer.buildSpan("llm-operation")
      .withServiceName("my-llm-service")
      .withSpanType(InternalSpanTypes.LLMOBS)
      .start()

    // Add LLM-specific tags with proper prefixes
    span.setTag("_ml_obs_tag.span.kind", Tags.LLMOBS_WORKFLOW_SPAN_KIND)
    span.setTag("_ml_obs_tag." + LLMObsTags.MODEL_NAME, "gpt-4")
    span.setTag("_ml_obs_tag." + LLMObsTags.MODEL_PROVIDER, "openai")
    span.setTag("_ml_obs_tag.input", "What is the weather?")
    span.setTag("_ml_obs_tag.output", "It's sunny today.")
    span.setTag("_ml_obs_tag.custom_tag", "test-value")
    span.setTag("_ml_obs_metric.input_tokens", 10.0)
    span.setTag("_ml_obs_metric.output_tokens", 5.0)
    span.setTag("_ml_obs_metric.total_cost", 0.005)

    // Add some metadata
    Map<String, Object> metadata = [
      "temperature": 0.7,
      "max_tokens": 100
    ]
    span.setTag("_ml_obs_tag." + LLMObsTags.METADATA, metadata)

    def trace = [span]

    when:
    LLMObsSpanMapper spanMapper = new LLMObsSpanMapper()
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer()
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, sink))
    packer.format(trace, spanMapper)
    packer.flush()

    then:
    sink.captured != null

    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(sink.captured)

    int topLevelMapSize = unpacker.unpackMapHeader()
    topLevelMapSize == 3

    Map<String, Object> topLevel = [:]
    for (int i = 0; i < topLevelMapSize; i++) {
      String key = unpacker.unpackString()
      if (key == "event_type") {
        topLevel[key] = unpacker.unpackString()
      } else if (key == "_dd.stage") {
        topLevel[key] = unpacker.unpackString()
      } else if (key == "spans") {
        int spansArraySize = unpacker.unpackArrayHeader()
        topLevel[key] = spansArraySize
        unpacker.skipValue() // TODO: add check for span data
      }
    }

    topLevel["event_type"] == "span"
    topLevel["_dd.stage"] == "raw"
    topLevel["spans"] == 1 // Should have 1 span

    cleanup:
    tracer.close()
  }

  def "test non-LLMObs span is filtered out"() {
    setup:
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    DDSpan regularSpan = (DDSpan) tracer.buildSpan("regular-operation")
      .withServiceName("my-service")
      .start()

    def trace = [regularSpan]

    when:
    LLMObsSpanMapper spanMapper = new LLMObsSpanMapper()
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer()
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, sink))
    packer.format(trace, spanMapper)
    packer.flush()

    then:
    sink.captured != null

    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(sink.captured)

    int topLevelMapSize = unpacker.unpackMapHeader()
    topLevelMapSize == 3

    Map<String, Object> topLevel = [:]
    for (int i = 0; i < topLevelMapSize; i++) {
      String key = unpacker.unpackString()
      if (key == "event_type") {
        topLevel[key] = unpacker.unpackString()
      } else if (key == "_dd.stage") {
        topLevel[key] = unpacker.unpackString()
      } else if (key == "spans") {
        int spansArraySize = unpacker.unpackArrayHeader()
        topLevel[key] = spansArraySize
        // Since array is empty, no need to skip anything
      }
    }

    // Verify that no spans are included since regular span is filtered out
    topLevel["spans"] == 0

    cleanup:
    tracer.close()
  }

  def "test LLM span with error"() {
    setup:
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    DDSpan span = (DDSpan) tracer.buildSpan("llm-operation")
      .withServiceName("my-llm-service")
      .withSpanType(InternalSpanTypes.LLMOBS)
      .start()

    span.setTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
    span.setError(true)
    span.setTag(DDTags.ERROR_MSG, "API rate limit exceeded")
    span.setTag(DDTags.ERROR_TYPE, "RateLimitError")
    span.setTag(DDTags.ERROR_STACK, "java.lang.RuntimeException: API rate limit exceeded")

    def trace = [span]

    when:
    LLMObsSpanMapper spanMapper = new LLMObsSpanMapper()
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer()
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, sink))
    packer.format(trace, spanMapper)
    packer.flush()

    then:
    sink.captured != null

    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(sink.captured)

    int topLevelMapSize = unpacker.unpackMapHeader()
    topLevelMapSize == 3

    Map<String, Object> topLevel = [:]
    for (int i = 0; i < topLevelMapSize; i++) {
      String key = unpacker.unpackString()
      if (key == "event_type") {
        topLevel[key] = unpacker.unpackString()
      } else if (key == "_dd.stage") {
        topLevel[key] = unpacker.unpackString()
      } else if (key == "spans") {
        int spansArraySize = unpacker.unpackArrayHeader()
        topLevel[key] = spansArraySize

        // Parse the spans array to check error information
        for (int spanIndex = 0; spanIndex < spansArraySize; spanIndex++) {
          int spanMapSize = unpacker.unpackMapHeader()
          spanMapSize == 11

          Map<String, Object> spanData = [:]
          for (int fieldIndex = 0; fieldIndex < spanMapSize; fieldIndex++) {
            String fieldKey = unpacker.unpackString()
            if (fieldKey == "error") {
              spanData[fieldKey] = unpacker.unpackInt()
            } else if (fieldKey == "status") {
              spanData[fieldKey] = unpacker.unpackString()
            } else if (fieldKey == "meta") {
              int metaMapSize = unpacker.unpackMapHeader()
              Map<String, String> metaMap = [:]
              for (int metaIndex = 0; metaIndex < metaMapSize; metaIndex++) {
                String metaKey = unpacker.unpackString()
                String metaValue = unpacker.unpackString()
                metaMap[metaKey] = metaValue
              }
              spanData[fieldKey] = metaMap
            } else {
              // Skip other fields
              unpacker.skipValue()
            }
          }

          // Verify error information
          spanData["error"] == 1
          spanData["status"] == "error"

          Map<String, String> meta = (Map<String, String>) spanData["meta"]
          meta[DDTags.ERROR_MSG] == "API rate limit exceeded"
          meta[DDTags.ERROR_TYPE] == "RateLimitError"
          meta[DDTags.ERROR_STACK] == "java.lang.RuntimeException: API rate limit exceeded"
        }
      }
    }

    topLevel["spans"] == 1

    cleanup:
    tracer.close()
  }

  static class CapturingByteBufferConsumer implements ByteBufferConsumer {

    ByteBuffer captured

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      captured = buffer
    }
  }
}
