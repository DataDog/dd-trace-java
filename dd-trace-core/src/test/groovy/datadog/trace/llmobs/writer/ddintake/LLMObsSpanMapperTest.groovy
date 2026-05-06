package datadog.trace.llmobs.writer.ddintake

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.api.DDTags
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

    def toolCall = LLMObs.ToolCall.from("get_weather", "function_call", "call_123", [location: "San Francisco"])
    def toolResult = LLMObs.ToolResult.from("get_weather", "function_call_output", "call_123", '{"temperature":"72F"}')
    def inputMessages = [
      LLMObs.LLMMessage.from("user", "Hello, what's the weather like?"),
      LLMObs.LLMMessage.from("assistant", null, [toolCall], [toolResult])
    ]
    def outputMessages = [LLMObs.LLMMessage.from("assistant", "I'll help you check the weather.")]
    llmSpan.setTag("_ml_obs_tag.input", [
      messages: inputMessages,
      prompt: [
        id: "prompt_123",
        version: "1",
        variables: [city: "San Francisco"],
        chat_template: [[role: "user", content: "Hello, what's the weather like in {{city}}?"]]
      ]
    ])
    llmSpan.setTag("_ml_obs_tag.output", outputMessages)
    llmSpan.setTag("_ml_obs_tag.metadata", [temperature: 0.7, max_tokens: 100])
    llmSpan.setTag("_ml_obs_tag.tool_definitions", [
      [
        name: "get_weather",
        description: "Get weather by city",
        schema: [type: "object", properties: [city: [type: "string"]]]
      ]
    ])
    llmSpan.setError(true)
    llmSpan.setTag(DDTags.ERROR_MSG, "boom")
    llmSpan.setTag(DDTags.ERROR_TYPE, "java.lang.IllegalStateException")
    llmSpan.setTag(DDTags.ERROR_STACK, "stacktrace")

    llmSpan.finish()

    def trace = [llmSpan]
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer()
    // Keep all formatted spans in a single flush for this assertion.
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(16 * 1024, sink))

    when:
    packer.format(trace, mapper)
    packer.flush()

    then:
    sink.captured != null
    def payload = mapper.newPayload()
    payload.withBody(1, sink.captured)

    // Capture the size before the buffer is written and the body buffer is emptied.
    def sizeInBytes = payload.sizeInBytes()

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
        boolean isOpen() {
          return true
        }

        @Override
        void close() throws IOException { }
      })

    def bytesWritten = channel.toByteArray()
    sizeInBytes == bytesWritten.length
    def result = objectMapper.readValue(bytesWritten, Map)

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
    spanData.containsKey("_dd")
    spanData["_dd"]["span_id"] == spanData["span_id"]
    spanData["_dd"]["trace_id"] == spanData["trace_id"]
    spanData["_dd"]["apm_trace_id"] == spanData["trace_id"]

    spanData.containsKey("meta")
    spanData["meta"]["span.kind"] == "llm"
    spanData["meta"].containsKey("error")
    spanData["meta"]["error"]["message"] == "boom"
    spanData["meta"]["error"]["type"] == "java.lang.IllegalStateException"
    spanData["meta"]["error"]["stack"] == "stacktrace"
    spanData["meta"].containsKey("input")
    spanData["meta"]["input"].containsKey("messages")
    spanData["meta"]["input"]["messages"][0].containsKey("content")
    spanData["meta"]["input"]["messages"][0]["content"] == "Hello, what's the weather like?"
    spanData["meta"]["input"]["messages"][0].containsKey("role")
    spanData["meta"]["input"]["messages"][0]["role"] == "user"
    spanData["meta"]["input"]["messages"][1]["role"] == "assistant"
    !spanData["meta"]["input"]["messages"][1].containsKey("content")
    spanData["meta"]["input"]["messages"][1]["tool_calls"][0]["name"] == "get_weather"
    spanData["meta"]["input"]["messages"][1]["tool_calls"][0]["type"] == "function_call"
    spanData["meta"]["input"]["messages"][1]["tool_calls"][0]["tool_id"] == "call_123"
    spanData["meta"]["input"]["messages"][1]["tool_calls"][0]["arguments"] == [location: "San Francisco"]
    spanData["meta"]["input"]["messages"][1]["tool_results"][0]["name"] == "get_weather"
    spanData["meta"]["input"]["messages"][1]["tool_results"][0]["type"] == "function_call_output"
    spanData["meta"]["input"]["messages"][1]["tool_results"][0]["tool_id"] == "call_123"
    spanData["meta"]["input"]["messages"][1]["tool_results"][0]["result"] == '{"temperature":"72F"}'
    spanData["meta"]["input"]["prompt"]["id"] == "prompt_123"
    spanData["meta"]["input"]["prompt"]["version"] == "1"
    spanData["meta"]["input"]["prompt"]["variables"] == [city: "San Francisco"]
    spanData["meta"]["input"]["prompt"]["chat_template"] == [[role: "user", content: "Hello, what's the weather like in {{city}}?"]]
    spanData["meta"].containsKey("output")
    spanData["meta"]["output"].containsKey("messages")
    spanData["meta"]["output"]["messages"][0].containsKey("content")
    spanData["meta"]["output"]["messages"][0]["content"] == "I'll help you check the weather."
    spanData["meta"]["output"]["messages"][0].containsKey("role")
    spanData["meta"]["output"]["messages"][0]["role"] == "assistant"
    spanData["meta"]["tool_definitions"][0]["name"] == "get_weather"
    spanData["meta"]["tool_definitions"][0]["description"] == "Get weather by city"
    spanData["meta"]["tool_definitions"][0]["schema"] == [type: "object", properties: [city: [type: "string"]]]
    spanData["meta"].containsKey("metadata")

    spanData.containsKey("metrics")
    spanData["metrics"]["input_tokens"] == 50.0
    spanData["metrics"]["output_tokens"] == 25.0
    spanData["metrics"]["total_tokens"] == 75.0

    spanData.containsKey("tags")
    spanData["tags"].contains("language:jvm")
  }

  def "test LLMObsSpanMapper writes no spans when none are LLMObs spans"() {
    setup:
    def mapper = new LLMObsSpanMapper()
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    def regularSpan1 = tracer.buildSpan("http.request")
      .withResourceName("GET /api/users")
      .withTag("http.method", "GET")
      .withTag("http.url", "https://example.com/api/users")
      .start()
    regularSpan1.finish()

    def regularSpan2 = tracer.buildSpan("database.query")
      .withResourceName("SELECT * FROM users")
      .withTag("db.type", "postgresql")
      .start()
    regularSpan2.finish()

    def trace = [regularSpan1, regularSpan2]
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer()
    // Keep all formatted spans in a single flush for this assertion.
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(16 * 1024, sink))

    when:
    packer.format(trace, mapper)
    packer.flush()

    then:
    sink.captured == null
  }

  def "test consecutive packer.format calls accumulate spans from multiple traces"() {
    setup:
    def mapper = new LLMObsSpanMapper()
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    // First trace with 2 LLMObs spans
    def llmSpan1 = tracer.buildSpan("chat-completion-1")
      .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
      .withTag("_ml_obs_tag.model_name", "gpt-4")
      .withTag("_ml_obs_tag.model_provider", "openai")
      .start()
    llmSpan1.setSpanType(InternalSpanTypes.LLMOBS)
    llmSpan1.finish()

    def llmSpan2 = tracer.buildSpan("chat-completion-2")
      .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
      .withTag("_ml_obs_tag.model_name", "gpt-3.5")
      .withTag("_ml_obs_tag.model_provider", "openai")
      .start()
    llmSpan2.setSpanType(InternalSpanTypes.LLMOBS)
    llmSpan2.finish()

    // Second trace with 1 LLMObs span
    def llmSpan3 = tracer.buildSpan("chat-completion-3")
      .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
      .withTag("_ml_obs_tag.model_name", "claude-3")
      .withTag("_ml_obs_tag.model_provider", "anthropic")
      .start()
    llmSpan3.setSpanType(InternalSpanTypes.LLMOBS)
    llmSpan3.finish()

    def trace1 = [llmSpan1, llmSpan2]
    def trace2 = [llmSpan3]
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer()
    // Keep all formatted spans in a single flush for this assertion.
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(16 * 1024, sink))

    when:
    packer.format(trace1, mapper)
    packer.format(trace2, mapper)
    packer.flush()

    then:
    sink.captured != null
    def payload = mapper.newPayload()
    payload.withBody(3, sink.captured)

    // Capture the size before the buffer is written and the body buffer is emptied.
    def sizeInBytes = payload.sizeInBytes()

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
        boolean isOpen() {
          return true
        }

        @Override
        void close() throws IOException { }
      })

    def bytesWritten = channel.toByteArray()
    sizeInBytes == bytesWritten.length
    def result = objectMapper.readValue(bytesWritten, Map)

    then:
    result.containsKey("event_type")
    result["event_type"] == "span"
    result.containsKey("_dd.stage")
    result["_dd.stage"] == "raw"
    result.containsKey("spans")
    result["spans"] instanceof List
    result["spans"].size() == 3

    def spanNames = result["spans"].collect { it["name"] }
    spanNames.contains("chat-completion-1")
    spanNames.contains("chat-completion-2")
    spanNames.contains("chat-completion-3")
  }

  static class CapturingByteBufferConsumer implements ByteBufferConsumer {

    ByteBuffer captured

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      captured = buffer
    }
  }
}
