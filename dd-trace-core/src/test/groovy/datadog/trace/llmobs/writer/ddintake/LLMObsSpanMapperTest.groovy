package datadog.trace.llmobs.writer.ddintake

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.api.llmobs.LLMObs
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification
import org.msgpack.jackson.dataformat.MessagePackFactory
import spock.lang.Shared

import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

class LLMObsSpanMapperTest extends DDCoreSpecification {

  @Shared
  ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory())

  def "test LLMObsSpanMapper serialization"() {
    setup:
    def mapper = new LLMObsSpanMapper()
    def tracer = tracerBuilder().writer(new ListWriter()).build()


    // Create a real LLMObs span using the tracer
    def llmSpan = tracer.buildSpan("openai.request")
      .withResourceName("createCompletion")
      .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
      .withTag("_ml_obs_tag.model_name", "gpt-4")
      .withTag("_ml_obs_tag.model_provider", "openai")
      .withTag("_ml_obs_metric.input_tokens", 50)
      .withTag("_ml_obs_metric.output_tokens", 25)
      .withTag("_ml_obs_metric.total_tokens", 75)
      .start()

    llmSpan.setSpanType(InternalSpanTypes.LLMOBS)

    def inputMessages = [LLMObs.LLMMessage.from("user", "Hello, what's the weather like?")]
    def outputMessages = [LLMObs.LLMMessage.from("assistant", "I'll help you check the weather.")]
    llmSpan.setTag("_ml_obs_tag.input", inputMessages)
    llmSpan.setTag("_ml_obs_tag.output", outputMessages)
    llmSpan.setTag("_ml_obs_tag.metadata", [temperature: 0.7, max_tokens: 100])

    llmSpan.finish()

    def trace = [llmSpan]
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer()
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, sink))

    when:
    packer.format(trace, mapper)
    packer.flush()

    then:
    sink.captured != null
    def payload = mapper.newPayload()
    payload.withBody(1, sink.captured)
    def channel = new ByteArrayOutputStream()
    payload.writeTo(new WritableByteChannel() {
        @Override
        int write(ByteBuffer src) throws IOException {
          def bytes = new byte[src.remaining()]
          src.get(bytes)
          channel.write(bytes)
          return bytes.length
        }

        @Override
        boolean isOpen() { return true }

        @Override
        void close() throws IOException { }
      })
    def result = objectMapper.readValue(channel.toByteArray(), Map)

    then:
    result.containsKey("event_type")
    result["event_type"] == "span"
    result.containsKey("_dd.stage")
    result["_dd.stage"] == "raw"
    result.containsKey("spans")
    result["spans"] instanceof List
    result["spans"].size() == 1

    def spanData = result["spans"][0]
    spanData["name"] == "OpenAI.createCompletion"
    spanData.containsKey("span_id")
    spanData.containsKey("trace_id")
    spanData.containsKey("start_ns")
    spanData.containsKey("duration")
    spanData["error"] == 0

    spanData.containsKey("meta")
    spanData["meta"]["span.kind"] == "llm"
    spanData["meta"].containsKey("input")
    spanData["meta"]["input"].containsKey("messages")
    spanData["meta"]["input"]["messages"][0].containsKey("content")
    spanData["meta"]["input"]["messages"][0]["content"] == "Hello, what's the weather like?"
    spanData["meta"]["input"]["messages"][0].containsKey("role")
    spanData["meta"]["input"]["messages"][0]["role"] == "user"
    spanData["meta"].containsKey("output")
    spanData["meta"]["output"].containsKey("messages")
    spanData["meta"]["output"]["messages"][0].containsKey("content")
    spanData["meta"]["output"]["messages"][0]["content"] == "I'll help you check the weather."
    spanData["meta"]["output"]["messages"][0].containsKey("role")
    spanData["meta"]["output"]["messages"][0]["role"] == "assistant"
    spanData["meta"].containsKey("metadata")

    spanData.containsKey("metrics")
    spanData["metrics"]["input_tokens"] == 50.0
    spanData["metrics"]["output_tokens"] == 25.0
    spanData["metrics"]["total_tokens"] == 75.0

    spanData.containsKey("tags")
    spanData["tags"].contains("language:jvm")
  }

  static class CapturingByteBufferConsumer implements ByteBufferConsumer {

    ByteBuffer captured

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      captured = buffer
    }
  }

}
