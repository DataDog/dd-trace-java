package datadog.trace.llmobs.writer.ddintake

import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTags
import datadog.trace.api.DDTraceId
import datadog.trace.api.llmobs.LLMObs
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification
import java.nio.ByteBuffer
import org.msgpack.core.MessagePack
import org.msgpack.core.buffer.ArrayBufferInput

class LLMObsSpanMapperTest extends DDCoreSpecification {

  def "serialize LLM obs span with id #value as int"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    def traceId = DDTraceId.from(value)
    def spanId = DDSpanId.from(value)
    def span = createLLMSpan("test-span", "gpt-4", "openai")
    CaptureBuffer capture = new CaptureBuffer()
    def packer = new MsgPackWriter(new FlushingBuffer(1024, capture))
    def mapper = new LLMObsSpanMapper()
    packer.format(Collections.singletonList(span), mapper)
    packer.flush()
    def unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes))
    int traceCount = capture.messageCount
    int spanCount = unpacker.unpackArrayHeader()

    expect:
    traceCount == 1
    spanCount == 1

    // Check the top-level map structure
    int topLevelSize = unpacker.unpackMapHeader()
    topLevelSize == 3

    // Check event_type
    String eventTypeKey = unpacker.unpackString()
    eventTypeKey == "event_type"
    String eventTypeValue = unpacker.unpackString()
    eventTypeValue == "span"

    // Check stage
    String stageKey = unpacker.unpackString()
    stageKey == "_dd.stage"
    String stageValue = unpacker.unpackString()
    stageValue == "raw"

    // Check spans
    String spansKey = unpacker.unpackString()
    spansKey == "spans"
    int spansArraySize = unpacker.unpackArrayHeader()
    spansArraySize == 1

    // Check span details
    int spanMapSize = unpacker.unpackMapHeader()
    spanMapSize == 11

    // Verify span_id
    String spanIdKey = unpacker.unpackString()
    spanIdKey == "span_id"
    String spanIdValue = unpacker.unpackString()
    spanIdValue == String.valueOf(spanId)

    // Verify trace_id
    String traceIdKey = unpacker.unpackString()
    traceIdKey == "trace_id"
    String traceIdValue = unpacker.unpackString()
    traceIdValue == traceId.toHexString()

    // Skip the rest of the span fields
    for (int i = 0; i < spanMapSize - 2; i++) {
      unpacker.unpackString() // key
      unpacker.unpackValue() // value
    }

    cleanup:
    tracer.close()

    where:
    value                                                | _
    "0"                                                  | _
  }

  def "serialize LLM obs span with tags and metrics"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    def span = createLLMSpan("test-span", "gpt-4", "openai")

    // Add LLM obs tags
    span.setTag("_ml_obs_tag.input", "test input")
    span.setTag("_ml_obs_tag.output", "test output")
    span.setTag("_ml_obs_tag.model.name", "gpt-4")
    span.setTag("_ml_obs_tag.model.provider", "openai")
    span.setTag("_ml_obs_tag.model.version", "1.0")
    span.setTag("_ml_obs_tag.metadata", ["key1": "value1", "key2": "value2"])

    // Add LLM obs metrics
    span.setMetric("tokens.prompt", 10)
    span.setMetric("tokens.completion", 20)
    span.setMetric("latency", 100.5)

    CaptureBuffer capture = new CaptureBuffer()
    def packer = new MsgPackWriter(new FlushingBuffer(1024, capture))
    def mapper = new LLMObsSpanMapper()
    packer.format(Collections.singletonList(span), mapper)
    packer.flush()
    def unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes))
    int traceCount = capture.messageCount
    int spanCount = unpacker.unpackArrayHeader()

    expect:
    traceCount == 1
    spanCount == 1

    // Skip to the span details
    unpacker.unpackMapHeader() // top-level map
    unpacker.unpackString() // event_type key
    unpacker.unpackString() // event_type value
    unpacker.unpackString() // stage key
    unpacker.unpackString() // stage value
    unpacker.unpackString() // spans key
    unpacker.unpackArrayHeader() // spans array
    unpacker.unpackMapHeader() // span map

    // Skip to metrics
    for (int i = 0; i < 8; i++) {
      unpacker.unpackString() // key
      unpacker.unpackValue() // value
    }

    // Check metrics
    String metricsKey = unpacker.unpackString()
    metricsKey == "metrics"
    int metricsSize = unpacker.unpackMapHeader()
    metricsSize == 3

    Map<String, Double> metrics = [:]
    for (int i = 0; i < metricsSize; i++) {
      String key = unpacker.unpackString()
      double value = unpacker.unpackDouble()
      metrics[key] = value
    }

    metrics["tokens.prompt"] == 10.0
    metrics["tokens.completion"] == 20.0
    metrics["latency"] == 100.5

    // Check tags
    String tagsKey = unpacker.unpackString()
    tagsKey == "tags"
    int tagsSize = unpacker.unpackArrayHeader()
    tagsSize == 1 // Only language:jvm

    String languageTag = unpacker.unpackString()
    languageTag == "language:jvm"

    // Check meta
    String metaKey = unpacker.unpackString()
    metaKey == "meta"
    int metaSize = unpacker.unpackMapHeader()
    metaSize == 7 // span.kind + 6 remapped tags

    // Check span.kind
    String spanKindKey = unpacker.unpackString()
    spanKindKey == "span.kind"
    String spanKindValue = unpacker.unpackString()
    spanKindValue == Tags.LLMOBS_LLM_SPAN_KIND

    // Check remapped tags
    Map<String, Object> meta = [:]
    for (int i = 0; i < metaSize - 1; i++) {
      String key = unpacker.unpackString()
      Object value = unpacker.unpackValue()
      meta[key] = value
    }

    meta["input"] == "test input"
    meta["output"] == "test output"
    meta["model.name"] == "gpt-4"
    meta["model.provider"] == "openai"
    meta["model.version"] == "1.0"

    // Check metadata
    meta.containsKey("metadata")
    Map<String, String> metadata = meta["metadata"] as Map<String, String>
    metadata["key1"] == "value1"
    metadata["key2"] == "value2"

    cleanup:
    tracer.close()
  }

  def "serialize LLM obs span with LLM messages"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    def span = createLLMSpan("test-span", "gpt-4", "openai")

    // Create LLM messages
    List<LLMObs.LLMMessage> messages = [
      LLMObs.LLMMessage.from("user", "Hello, how are you?"),
      LLMObs.LLMMessage.from("system", "Answer the user truthfully")
    ]

    span.annotateIO(messages, Collections.singletonList(
      LLMObs.LLMMessage.from("assistant", "I'm doing well, thank you for asking!")
    ))

    CaptureBuffer capture = new CaptureBuffer()
    def packer = new MsgPackWriter(new FlushingBuffer(1024, capture))
    def mapper = new LLMObsSpanMapper()
    packer.format(Collections.singletonList(span), mapper)
    packer.flush()
    def unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes))
    int traceCount = capture.messageCount
    int spanCount = unpacker.unpackArrayHeader()

    expect:
    traceCount == 1
    spanCount == 1

    // Skip to the meta section
    unpacker.unpackMapHeader() // top-level map
    unpacker.unpackString() // event_type key
    unpacker.unpackString() // event_type value
    unpacker.unpackString() // stage key
    unpacker.unpackString() // stage value
    unpacker.unpackString() // spans key
    unpacker.unpackArrayHeader() // spans array
    unpacker.unpackMapHeader() // span map

    // Skip to meta
    for (int i = 0; i < 10; i++) {
      unpacker.unpackString() // key
      unpacker.unpackValue() // value
    }

    // Check meta
    String metaKey = unpacker.unpackString()
    metaKey == "meta"
    int metaSize = unpacker.unpackMapHeader()
    metaSize == 3 // span.kind + input + output

    // Check span.kind
    String spanKindKey = unpacker.unpackString()
    spanKindKey == "span.kind"
    String spanKindValue = unpacker.unpackString()
    spanKindValue == Tags.LLMOBS_LLM_SPAN_KIND

    // Check input messages
    String inputKey = unpacker.unpackString()
    inputKey == "input.messages"
    int inputMessagesSize = unpacker.unpackArrayHeader()
    inputMessagesSize == 2

    // Check first message
    int firstMessageSize = unpacker.unpackMapHeader()
    firstMessageSize == 2

    String userRoleKey = unpacker.unpackString()
    userRoleKey == "role"
    String userRoleValue = unpacker.unpackString()
    userRoleValue == "user"

    String userContentKey = unpacker.unpackString()
    userContentKey == "content"
    String userContentValue = unpacker.unpackString()
    userContentValue == "Hello, how are you?"

    // Check second message
    int secondMessageSize = unpacker.unpackMapHeader()
    secondMessageSize == 2

    String sysRoleKey = unpacker.unpackString()
    sysRoleKey == "role"
    String sysRoleValue = unpacker.unpackString()
    sysRoleValue == "system"

    String sysContentKey = unpacker.unpackString()
    sysContentKey == "content"
    String sysContentValue = unpacker.unpackString()
    sysContentValue == "I'm doing well, thank you for asking!"


    // Check output messages
    String outputKey = unpacker.unpackString()
    outputKey == "output.messages"
    int outputMessagesSize = unpacker.unpackArrayHeader()
    outputMessagesSize == 1

    // Check output message
    int outputMessageSize = unpacker.unpackMapHeader()
    outputMessageSize == 2

    String assistantRoleKey = unpacker.unpackString()
    assistantRoleKey == "role"
    String assistantRoleValue = unpacker.unpackString()
    assistantRoleValue == "assistant"

    String assistantContentKey = unpacker.unpackString()
    assistantContentKey == "content"
    String assistantContentValue = unpacker.unpackString()
    assistantContentValue == "I'm doing well, thank you for asking!"

    cleanup:
    tracer.close()
  }
//
//  def "serialize LLM obs span with tool calls"() {
//    setup:
//    def writer = new ListWriter()
//    def tracer = tracerBuilder().writer(writer).build()
//    def span = createLLMSpan("test-span", "gpt-4", "openai")
//
//    // Create LLM messages with tool calls
//    List<LLMObs.ToolCall> toolCalls = [
//      LLMObs.ToolCall.from("search", "function", "search-123", ["query": "test query"]),
//      LLMObs.ToolCall.from("calculator", "function", "calc-456", ["operation": "add", "a": 5, "b": 3])
//    ]
//
//    List<LLMObs.LLMMessage> messages = [
//      LLMObs.LLMMessage.from("assistant", "Let me search for that information.", toolCalls)
//    ]
//
//    // Add LLM obs tags with messages
//    span.annotateIO(null, messages)
//
//    CaptureBuffer capture = new CaptureBuffer()
//    def packer = new MsgPackWriter(new FlushingBuffer(1024, capture))
//    def mapper = new LLMObsSpanMapper()
//    packer.format(Collections.singletonList(span), mapper)
//    packer.flush()
//    def unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes))
//    int traceCount = capture.messageCount
//    int spanCount = unpacker.unpackArrayHeader()
//
//    expect:
//    traceCount == 1
//    spanCount == 1
//
//    // Skip to the meta section
//    unpacker.unpackMapHeader() // top-level map
//    unpacker.unpackString() // event_type key
//    unpacker.unpackString() // event_type value
//    unpacker.unpackString() // stage key
//    unpacker.unpackString() // stage value
//    unpacker.unpackString() // spans key
//    unpacker.unpackArrayHeader() // spans array
//    unpacker.unpackMapHeader() // span map
//
//    // Skip to meta
//    for (int i = 0; i < 10; i++) {
//      unpacker.unpackString() // key
//      unpacker.unpackValue() // value
//    }
//
//    // Check meta
//    String metaKey = unpacker.unpackString()
//    metaKey == "meta"
//    int metaSize = unpacker.unpackMapHeader()
//    metaSize == 2 // span.kind + output
//
//    // Check span.kind
//    String spanKindKey = unpacker.unpackString()
//    spanKindKey == "span.kind"
//    String spanKindValue = unpacker.unpackString()
//    spanKindValue == Tags.LLMOBS_LLM_SPAN_KIND
//
//    // Check output messages
//    String outputKey = unpacker.unpackString()
//    outputKey == "output.messages"
//    int outputMessagesSize = unpacker.unpackArrayHeader()
//    outputMessagesSize == 1
//
//    // Check output message
//    int outputMessageSize = unpacker.unpackMapHeader()
//    outputMessageSize == 3 // role, content, tool_calls
//
//    String roleKey = unpacker.unpackString()
//    roleKey == "role"
//    String roleValue = unpacker.unpackString()
//    roleValue == "assistant"
//
//    String contentKey = unpacker.unpackString()
//    contentKey == "content"
//    String contentValue = unpacker.unpackString()
//    contentValue == "Let me search for that information."
//
//    // Check tool calls
//    String toolCallsKey = unpacker.unpackString()
//    toolCallsKey == "tool_calls"
//    int toolCallsSize = unpacker.unpackArrayHeader()
//    toolCallsSize == 2
//
//    // Check first tool call
//    int firstToolCallSize = unpacker.unpackMapHeader()
//    firstToolCallSize == 4 // name, type, tool_id, arguments
//
//    String nameKey = unpacker.unpackString()
//    nameKey == "name"
//    String nameValue = unpacker.unpackString()
//    nameValue == "search"
//
//    String typeKey = unpacker.unpackString()
//    typeKey == "type"
//    String typeValue = unpacker.unpackString()
//    typeValue == "function"
//
//    String toolIdKey = unpacker.unpackString()
//    toolIdKey == "tool_id"
//    String toolIdValue = unpacker.unpackString()
//    toolIdValue == "search-123"
//
//    String argumentsKey = unpacker.unpackString()
//    argumentsKey == "arguments"
//    int argumentsSize = unpacker.unpackMapHeader()
//    argumentsSize == 1
//
//    String queryKey = unpacker.unpackString()
//    queryKey == "query"
//    String queryValue = unpacker.unpackString()
//    queryValue == "test query"
//
//    // Check second tool call
//    int secondToolCallSize = unpacker.unpackMapHeader()
//    secondToolCallSize == 4 // name, type, tool_id, arguments
//
//    nameKey = unpacker.unpackString()
//    nameKey == "name"
//    nameValue = unpacker.unpackString()
//    nameValue == "calculator"
//
//    typeKey = unpacker.unpackString()
//    typeKey == "type"
//    typeValue = unpacker.unpackString()
//    typeValue == "function"
//
//    toolIdKey = unpacker.unpackString()
//    toolIdKey == "tool_id"
//    toolIdValue = unpacker.unpackString()
//    toolIdValue == "calc-456"
//
//    argumentsKey = unpacker.unpackString()
//    argumentsKey == "arguments"
//    argumentsSize = unpacker.unpackMapHeader()
//    argumentsSize == 3
//
//    String operationKey = unpacker.unpackString()
//    operationKey == "operation"
//    String operationValue = unpacker.unpackString()
//    operationValue == "add"
//
//    String aKey = unpacker.unpackString()
//    aKey == "a"
//    int aValue = unpacker.unpackInt()
//    aValue == 5
//
//    String bKey = unpacker.unpackString()
//    bKey == "b"
//    int bValue = unpacker.unpackInt()
//    bValue == 3
//
//    cleanup:
//    tracer.close()
//  }

  def "serialize LLM obs span with error information"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    def span = createLLMSpan("test-span", "gpt-4", "openai")

    // Add error information
    span.setErrorMessage("Something went wrong")
    span.setError(true)

    CaptureBuffer capture = new CaptureBuffer()
    def packer = new MsgPackWriter(new FlushingBuffer(1024, capture))
    def mapper = new LLMObsSpanMapper()
    packer.format(Collections.singletonList(span), mapper)
    packer.flush()
    def unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes))
    int traceCount = capture.messageCount
    int spanCount = unpacker.unpackArrayHeader()

    expect:
    traceCount == 1
    spanCount == 1

    // Skip to the span details
    unpacker.unpackMapHeader() // top-level map
    unpacker.unpackString() // event_type key
    unpacker.unpackString() // event_type value
    unpacker.unpackString() // stage key
    unpacker.unpackString() // stage value
    unpacker.unpackString() // spans key
    unpacker.unpackArrayHeader() // spans array
    unpacker.unpackMapHeader() // span map

    // Check error field
    for (int i = 0; i < 6; i++) {
      unpacker.unpackString() // key
      unpacker.unpackValue() // value
    }

    String errorKey = unpacker.unpackString()
    errorKey == "error"
    int errorValue = unpacker.unpackInt()
    errorValue == 1

    // Check status field
    String statusKey = unpacker.unpackString()
    statusKey == "status"
    String statusValue = unpacker.unpackString()
    statusValue == "error"

    // Skip to meta
    for (int i = 0; i < 2; i++) {
      unpacker.unpackString() // key
      unpacker.unpackValue() // value
    }

    // Check meta
    String metaKey = unpacker.unpackString()
    metaKey == "meta"
    int metaSize = unpacker.unpackMapHeader()
    metaSize == 2 // span.kind + error.msg

    // Check error fields
    Map<String, String> errorFields = [:]
    for (int i = 0; i < metaSize; i++) {
      String key = unpacker.unpackString()
      String value = unpacker.unpackString()
      errorFields[key] = value
    }

    errorFields[DDTags.ERROR_MSG] == "Something went wrong"

    cleanup:
    tracer.close()
  }

  private class CaptureBuffer implements ByteBufferConsumer {

    private byte[] bytes
    int messageCount

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      this.messageCount = messageCount
      this.bytes = new byte[buffer.limit() - buffer.position()]
      buffer.get(bytes)
    }
  }

  def createLLMSpan(String spanName, String modelName, String modelProvider) {
    // Use the LLMObs API to create a span
    return LLMObs.startLLMSpan(spanName, modelName, modelProvider, "test-app", "test-session")
  }

  def createAgentSpan(String spanName) {
    // Use the LLMObs API to create an agent span
    return LLMObs.startAgentSpan(spanName, "test-app", "test-session")
  }

  def createToolSpan(String spanName) {
    // Use the LLMObs API to create a tool span
    return LLMObs.startToolSpan(spanName, "test-app", "test-session")
  }

  def createTaskSpan(String spanName) {
    // Use the LLMObs API to create a task span
    return LLMObs.startTaskSpan(spanName, "test-app", "test-session")
  }

  def createWorkflowSpan(String spanName) {
    // Use the LLMObs API to create a workflow span
    return LLMObs.startWorkflowSpan(spanName, "test-app", "test-session")
  }
}
