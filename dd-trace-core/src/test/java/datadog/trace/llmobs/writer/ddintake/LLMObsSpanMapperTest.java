package datadog.trace.llmobs.writer.ddintake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.DDTags;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.telemetry.LLMObsMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.msgpack.jackson.dataformat.MessagePackFactory;

@SuppressWarnings("unchecked")
public class LLMObsSpanMapperTest extends DDCoreJavaSpecification {

  private static final ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory());

  @Test
  void testLLMObsSpanMapperSerialization() throws Exception {
    LLMObsSpanMapper mapper = new LLMObsSpanMapper();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    // Create a real LLMObs span using the tracer
    AgentSpan llmSpan =
        tracer
            .buildSpan("datadog", "openai.request")
            .withResourceName("createCompletion")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
            .withTag("_ml_obs_tag.model_name", "gpt-4")
            .withTag("_ml_obs_tag.model_provider", "openai")
            .withTag("_ml_obs_metric.input_tokens", 50)
            .withTag("_ml_obs_metric.output_tokens", 25)
            .withTag("_ml_obs_metric.total_tokens", 75)
            .withTag("_ml_obs_tag.session_id", "abc-123-session")
            .start();

    llmSpan.setSpanType(InternalSpanTypes.LLMOBS);

    Map<String, Object> toolCallArgs = Collections.singletonMap("location", "San Francisco");
    LLMObs.ToolCall toolCall =
        LLMObs.ToolCall.from("get_weather", "function_call", "call_123", toolCallArgs);
    LLMObs.ToolResult toolResult =
        LLMObs.ToolResult.from(
            "get_weather", "function_call_output", "call_123", "{\"temperature\":\"72F\"}");
    List<LLMObs.LLMMessage> inputMessages =
        Arrays.asList(
            LLMObs.LLMMessage.from("user", "Hello, what's the weather like?"),
            LLMObs.LLMMessage.from(
                "assistant",
                null,
                Collections.singletonList(toolCall),
                Collections.singletonList(toolResult)));
    List<LLMObs.LLMMessage> outputMessages =
        Collections.singletonList(
            LLMObs.LLMMessage.from("assistant", "I'll help you check the weather."));

    Map<String, Object> chatTemplateEntry = new LinkedHashMap<>();
    chatTemplateEntry.put("role", "user");
    chatTemplateEntry.put("content", "Hello, what's the weather like in {{city}}?");
    Map<String, Object> prompt = new LinkedHashMap<>();
    prompt.put("id", "prompt_123");
    prompt.put("version", "1");
    prompt.put("variables", Collections.singletonMap("city", "San Francisco"));
    prompt.put("chat_template", Collections.singletonList(chatTemplateEntry));

    llmSpan.setTag("_ml_obs_tag.input", inputMessages);
    llmSpan.setTag("_ml_obs_tag.input_prompt", prompt);
    llmSpan.setTag("_ml_obs_tag.output", outputMessages);

    Map<String, Object> metadataMap = new LinkedHashMap<>();
    metadataMap.put("temperature", 0.7);
    metadataMap.put("max_tokens", 100);
    llmSpan.setTag("_ml_obs_tag.metadata", metadataMap);

    Map<String, Object> cityProp = Collections.singletonMap("type", "string");
    Map<String, Object> properties = Collections.singletonMap("city", cityProp);
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    LLMObs.ToolDefinition toolDefinition =
        LLMObs.ToolDefinition.from("get_weather", "Get weather by city", schema, "1.2.3");
    llmSpan.setTag(
        "_ml_obs_tag.tool_definitions",
        Arrays.asList(toolDefinition, LLMObs.ToolDefinition.from("get_time")));

    llmSpan.setError(true);
    llmSpan.setTag(DDTags.ERROR_MSG, "boom");
    llmSpan.setTag(DDTags.ERROR_TYPE, "java.lang.IllegalStateException");
    llmSpan.setTag(DDTags.ERROR_STACK, "stacktrace");

    llmSpan.finish();

    List<DDSpan> trace = Collections.singletonList((DDSpan) llmSpan);
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer();
    // Keep all formatted spans in a single flush for this assertion.
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(16 * 1024, sink));

    packer.format(trace, mapper);
    packer.flush();

    assertNotNull(sink.captured);
    datadog.trace.common.writer.Payload payload = mapper.newPayload();
    payload.withBody(1, sink.captured);

    // Capture the size before the buffer is written and the body buffer is emptied.
    int sizeInBytes = payload.sizeInBytes();

    byte[] bytesWritten = writeTo(payload);
    assertEquals(sizeInBytes, bytesWritten.length);
    Map<String, Object> result = objectMapper.readValue(bytesWritten, Map.class);

    assertTrue(result.containsKey("event_type"));
    assertEquals("span", result.get("event_type"));
    assertTrue(result.containsKey("_dd.stage"));
    assertEquals("raw", result.get("_dd.stage"));
    assertTrue(result.containsKey("spans"));
    assertNotNull(result.get("spans"));
    List<Map<String, Object>> spans = (List<Map<String, Object>>) result.get("spans");
    assertTrue(spans instanceof List);
    assertEquals(1, spans.size());

    Map<String, Object> spanData = spans.get(0);
    assertEquals("OpenAI.createCompletion", spanData.get("name"));
    assertTrue(spanData.containsKey("span_id"));
    assertTrue(spanData.containsKey("trace_id"));
    assertTrue(spanData.containsKey("start_ns"));
    assertTrue(spanData.containsKey("duration"));
    assertTrue(spanData.containsKey("_dd"));
    Map<String, Object> dd = (Map<String, Object>) spanData.get("_dd");
    assertEquals(dd.get("span_id"), spanData.get("span_id"));
    assertEquals(dd.get("trace_id"), spanData.get("trace_id"));
    assertEquals(dd.get("apm_trace_id"), spanData.get("trace_id"));

    // Top-level session_id field — what the LLM Trace Explorer's Sessions filter queries.
    assertTrue(spanData.containsKey("session_id"));
    assertEquals("abc-123-session", spanData.get("session_id"));

    assertTrue(spanData.containsKey("meta"));
    Map<String, Object> meta = (Map<String, Object>) spanData.get("meta");
    assertEquals("llm", meta.get("span.kind"));
    assertTrue(meta.containsKey("error"));
    Map<String, Object> error = (Map<String, Object>) meta.get("error");
    assertEquals("boom", error.get("message"));
    assertEquals("java.lang.IllegalStateException", error.get("type"));
    assertEquals("stacktrace", error.get("stack"));
    assertTrue(meta.containsKey("input"));
    Map<String, Object> inputResult = (Map<String, Object>) meta.get("input");
    assertTrue(inputResult.containsKey("messages"));
    List<Map<String, Object>> inputMsgs = (List<Map<String, Object>>) inputResult.get("messages");
    assertTrue(inputMsgs.get(0).containsKey("content"));
    assertEquals("Hello, what's the weather like?", inputMsgs.get(0).get("content"));
    assertTrue(inputMsgs.get(0).containsKey("role"));
    assertEquals("user", inputMsgs.get(0).get("role"));
    assertEquals("assistant", inputMsgs.get(1).get("role"));
    assertFalse(inputMsgs.get(1).containsKey("content"));
    List<Map<String, Object>> toolCalls =
        (List<Map<String, Object>>) inputMsgs.get(1).get("tool_calls");
    assertEquals("get_weather", toolCalls.get(0).get("name"));
    assertEquals("function_call", toolCalls.get(0).get("type"));
    assertEquals("call_123", toolCalls.get(0).get("tool_id"));
    assertEquals(
        Collections.singletonMap("location", "San Francisco"), toolCalls.get(0).get("arguments"));
    List<Map<String, Object>> toolResults =
        (List<Map<String, Object>>) inputMsgs.get(1).get("tool_results");
    assertEquals("get_weather", toolResults.get(0).get("name"));
    assertEquals("function_call_output", toolResults.get(0).get("type"));
    assertEquals("call_123", toolResults.get(0).get("tool_id"));
    assertEquals("{\"temperature\":\"72F\"}", toolResults.get(0).get("result"));
    Map<String, Object> promptResult = (Map<String, Object>) inputResult.get("prompt");
    assertEquals("prompt_123", promptResult.get("id"));
    assertEquals("1", promptResult.get("version"));
    assertEquals(Collections.singletonMap("city", "San Francisco"), promptResult.get("variables"));
    assertEquals(Collections.singletonList(chatTemplateEntry), promptResult.get("chat_template"));
    assertTrue(meta.containsKey("output"));
    Map<String, Object> outputResult = (Map<String, Object>) meta.get("output");
    assertTrue(outputResult.containsKey("messages"));
    assertFalse(outputResult.containsKey("prompt"));
    List<Map<String, Object>> outputMsgs = (List<Map<String, Object>>) outputResult.get("messages");
    assertTrue(outputMsgs.get(0).containsKey("content"));
    assertEquals("I'll help you check the weather.", outputMsgs.get(0).get("content"));
    assertTrue(outputMsgs.get(0).containsKey("role"));
    assertEquals("assistant", outputMsgs.get(0).get("role"));
    List<Map<String, Object>> toolDefsResult =
        (List<Map<String, Object>>) meta.get("tool_definitions");
    assertEquals(2, toolDefsResult.size());
    assertEquals("get_weather", toolDefsResult.get(0).get("name"));
    assertEquals("Get weather by city", toolDefsResult.get(0).get("description"));
    assertEquals(schema, toolDefsResult.get(0).get("schema"));
    assertEquals("1.2.3", toolDefsResult.get(0).get("version"));
    assertEquals(Collections.singletonMap("name", "get_time"), toolDefsResult.get(1));
    assertTrue(meta.containsKey("metadata"));

    assertTrue(spanData.containsKey("metrics"));
    Map<String, Object> metrics = (Map<String, Object>) spanData.get("metrics");
    assertEquals(50.0, ((Number) metrics.get("input_tokens")).doubleValue(), 0.0);
    assertEquals(25.0, ((Number) metrics.get("output_tokens")).doubleValue(), 0.0);
    assertEquals(75.0, ((Number) metrics.get("total_tokens")).doubleValue(), 0.0);

    assertTrue(spanData.containsKey("tags"));
    List<String> tags = (List<String>) spanData.get("tags");
    assertTrue(tags.contains("language:jvm"));
    assertTrue(tags.contains("session_id:abc-123-session"));
    assertFalse(tags.stream().anyMatch(tag -> tag.startsWith("input_prompt:")));

    tracer.close();
  }

  @Test
  void testLLMObsSpanMapperSerializesPromptWithoutInputMessages() throws Exception {
    LLMObsSpanMapper mapper = new LLMObsSpanMapper();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    Map<String, Object> prompt = new LinkedHashMap<>();
    prompt.put("id", "prompt_123");
    prompt.put("template", "Hello {{name}}");
    prompt.put("variables", Collections.singletonMap("name", "Sam"));

    AgentSpan llmSpan =
        tracer
            .buildSpan("datadog", "chat-completion")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
            .withTag("_ml_obs_tag.input_prompt", prompt)
            .start();
    llmSpan.setSpanType(InternalSpanTypes.LLMOBS);
    llmSpan.finish();

    Map<String, Object> spanData = serializeSingleSpan(mapper, llmSpan);
    Map<String, Object> meta = (Map<String, Object>) spanData.get("meta");
    Map<String, Object> input = (Map<String, Object>) meta.get("input");

    assertEquals(Collections.singletonMap("prompt", prompt), input);
    List<String> tags = (List<String>) spanData.get("tags");
    assertFalse(tags.stream().anyMatch(tag -> tag.startsWith("input_prompt:")));

    tracer.close();
  }

  @Test
  void testLLMObsSpanMapperPreservesNestedPromptInputCompatibility() throws Exception {
    LLMObsSpanMapper mapper = new LLMObsSpanMapper();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    Map<String, Object> prompt = Collections.singletonMap("id", "legacy_prompt");
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("messages", Collections.singletonList(LLMObs.LLMMessage.from("user", "Hello")));
    input.put("prompt", prompt);

    AgentSpan llmSpan =
        tracer
            .buildSpan("datadog", "chat-completion")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
            .withTag("_ml_obs_tag.input", input)
            .start();
    llmSpan.setSpanType(InternalSpanTypes.LLMOBS);
    llmSpan.finish();

    Map<String, Object> spanData = serializeSingleSpan(mapper, llmSpan);
    Map<String, Object> meta = (Map<String, Object>) spanData.get("meta");
    Map<String, Object> serializedInput = (Map<String, Object>) meta.get("input");

    assertEquals(prompt, serializedInput.get("prompt"));
    assertTrue(serializedInput.containsKey("messages"));

    tracer.close();
  }

  @Test
  void testLLMObsSpanMapperWritesNoSpansWhenNoneAreLLMObsSpans() {
    LLMObsSpanMapper mapper = new LLMObsSpanMapper();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    AgentSpan regularSpan1 =
        tracer
            .buildSpan("datadog", "http.request")
            .withResourceName("GET /api/users")
            .withTag("http.method", "GET")
            .withTag("http.url", "https://example.com/api/users")
            .start();
    regularSpan1.finish();

    AgentSpan regularSpan2 =
        tracer
            .buildSpan("datadog", "database.query")
            .withResourceName("SELECT * FROM users")
            .withTag("db.type", "postgresql")
            .start();
    regularSpan2.finish();

    List<DDSpan> trace = Arrays.asList((DDSpan) regularSpan1, (DDSpan) regularSpan2);
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer();
    // Keep all formatted spans in a single flush for this assertion.
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(16 * 1024, sink));

    packer.format(trace, mapper);
    packer.flush();

    assertFalse(sink.captured != null);

    tracer.close();
  }

  @Test
  void testConsecutivePackerFormatCallsAccumulateSpansFromMultipleTraces() throws Exception {
    LLMObsSpanMapper mapper = new LLMObsSpanMapper();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    // First trace with 2 LLMObs spans
    AgentSpan llmSpan1 =
        tracer
            .buildSpan("datadog", "chat-completion-1")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
            .withTag("_ml_obs_tag.model_name", "gpt-4")
            .withTag("_ml_obs_tag.model_provider", "openai")
            .start();
    llmSpan1.setSpanType(InternalSpanTypes.LLMOBS);
    llmSpan1.finish();

    AgentSpan llmSpan2 =
        tracer
            .buildSpan("datadog", "chat-completion-2")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
            .withTag("_ml_obs_tag.model_name", "gpt-3.5")
            .withTag("_ml_obs_tag.model_provider", "openai")
            .start();
    llmSpan2.setSpanType(InternalSpanTypes.LLMOBS);
    llmSpan2.finish();

    // Second trace with 1 LLMObs span
    AgentSpan llmSpan3 =
        tracer
            .buildSpan("datadog", "chat-completion-3")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
            .withTag("_ml_obs_tag.model_name", "claude-3")
            .withTag("_ml_obs_tag.model_provider", "anthropic")
            .start();
    llmSpan3.setSpanType(InternalSpanTypes.LLMOBS);
    llmSpan3.finish();

    List<DDSpan> trace1 = Arrays.asList((DDSpan) llmSpan1, (DDSpan) llmSpan2);
    List<DDSpan> trace2 = Collections.singletonList((DDSpan) llmSpan3);
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer();
    // Keep all formatted spans in a single flush for this assertion.
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(16 * 1024, sink));

    packer.format(trace1, mapper);
    packer.format(trace2, mapper);
    packer.flush();

    assertNotNull(sink.captured);
    datadog.trace.common.writer.Payload payload = mapper.newPayload();
    payload.withBody(3, sink.captured);

    // Capture the size before the buffer is written and the body buffer is emptied.
    int sizeInBytes = payload.sizeInBytes();

    byte[] bytesWritten = writeTo(payload);
    assertEquals(sizeInBytes, bytesWritten.length);
    Map<String, Object> result = objectMapper.readValue(bytesWritten, Map.class);

    assertTrue(result.containsKey("event_type"));
    assertEquals("span", result.get("event_type"));
    assertTrue(result.containsKey("_dd.stage"));
    assertEquals("raw", result.get("_dd.stage"));
    assertTrue(result.containsKey("spans"));
    List<Map<String, Object>> spans = (List<Map<String, Object>>) result.get("spans");
    assertTrue(spans instanceof List);
    assertEquals(3, spans.size());

    List<Object> spanNames = new ArrayList<>();
    for (Map<String, Object> span : spans) {
      spanNames.add(span.get("name"));
    }
    assertTrue(spanNames.contains("chat-completion-1"));
    assertTrue(spanNames.contains("chat-completion-2"));
    assertTrue(spanNames.contains("chat-completion-3"));

    tracer.close();
  }

  @Test
  void testLLMObsSpanMapperOmitsTopLevelSessionIdWhenNotSet() throws Exception {
    LLMObsSpanMapper mapper = new LLMObsSpanMapper();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    AgentSpan llmSpan =
        tracer
            .buildSpan("datadog", "openai.request")
            .withResourceName("createCompletion")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
            .withTag("_ml_obs_tag.model_name", "gpt-4")
            .withTag("_ml_obs_tag.model_provider", "openai")
            .start();
    llmSpan.setSpanType(InternalSpanTypes.LLMOBS);
    llmSpan.finish();

    List<DDSpan> trace = Collections.singletonList((DDSpan) llmSpan);
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(16 * 1024, sink));

    packer.format(trace, mapper);
    packer.flush();

    assertNotNull(sink.captured);
    datadog.trace.common.writer.Payload payload = mapper.newPayload();
    payload.withBody(1, sink.captured);

    byte[] bytesWritten = writeTo(payload);
    Map<String, Object> result = objectMapper.readValue(bytesWritten, Map.class);
    List<Map<String, Object>> spans = (List<Map<String, Object>>) result.get("spans");
    Map<String, Object> spanData = spans.get(0);

    // No top-level session_id field when the tag was never set.
    assertFalse(spanData.containsKey("session_id"));

    // And no session_id entry leaks into tags[] either.
    List<String> tags = (List<String>) spanData.get("tags");
    for (String tag : tags) {
      assertFalse(
          tag.startsWith("session_id:"), "tag should not start with session_id: but got: " + tag);
    }

    tracer.close();
  }

  @Test
  void testLLMObsSpanProcessorModifiesInputAndOutput() throws Exception {
    LLMObs.registerProcessor(
        span -> {
          assertEquals(Tags.LLMOBS_LLM_SPAN_KIND, span.getKind());
          assertEquals("true", span.getTag("redact"));
          assertEquals("secret input", span.getInput().get(0).getContent());
          span.setInput(Collections.singletonList(LLMObs.LLMMessage.from("user", "[REDACTED]")));
          span.setOutput(Collections.emptyList());
          return span;
        });

    try {
      CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
      Map<String, Object> originalInput = new LinkedHashMap<>();
      originalInput.put(
          "messages", Collections.singletonList(LLMObs.LLMMessage.from("user", "secret input")));
      originalInput.put("prompt", Collections.singletonMap("id", "prompt-id"));
      AgentSpan llmSpan =
          tracer
              .buildSpan("datadog", "processed")
              .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
              .withTag("_ml_obs_tag.input", originalInput)
              .withTag(
                  "_ml_obs_tag.output",
                  Collections.singletonList(LLMObs.LLMMessage.from("assistant", "secret output")))
              .withTag("_ml_obs_tag.redact", true)
              .start();
      llmSpan.setSpanType(InternalSpanTypes.LLMOBS);
      llmSpan.finish();

      List<Map<String, Object>> spans =
          serialize(Collections.singletonList((DDSpan) llmSpan), new LLMObsSpanMapper());
      Map<String, Object> meta = (Map<String, Object>) spans.get(0).get("meta");
      Map<String, Object> input = (Map<String, Object>) meta.get("input");
      List<Map<String, Object>> messages = (List<Map<String, Object>>) input.get("messages");

      assertEquals("[REDACTED]", messages.get(0).get("content"));
      assertEquals(Collections.singletonMap("id", "prompt-id"), input.get("prompt"));
      assertFalse(meta.containsKey("output"));
      tracer.close();
    } finally {
      LLMObs.deregisterProcessor();
    }
  }

  @Test
  void testLLMObsSpanProcessorAddsMissingInputAndOutput() throws Exception {
    LLMObs.registerProcessor(
        span -> {
          span.setInput(Collections.singletonList(LLMObs.LLMMessage.from("user", "added input")));
          span.setOutput(
              Collections.singletonList(LLMObs.LLMMessage.from("assistant", "added output")));
          return span;
        });

    try {
      CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
      AgentSpan llmSpan = newLlmObsSpan(tracer, "processed", false);

      List<Map<String, Object>> spans =
          serialize(Collections.singletonList((DDSpan) llmSpan), new LLMObsSpanMapper());
      Map<String, Object> meta = (Map<String, Object>) spans.get(0).get("meta");
      Map<String, Object> input = (Map<String, Object>) meta.get("input");
      Map<String, Object> output = (Map<String, Object>) meta.get("output");
      List<Map<String, Object>> inputMessages = (List<Map<String, Object>>) input.get("messages");
      List<Map<String, Object>> outputMessages = (List<Map<String, Object>>) output.get("messages");

      assertEquals("added input", inputMessages.get(0).get("content"));
      assertEquals("added output", outputMessages.get(0).get("content"));
      tracer.close();
    } finally {
      LLMObs.deregisterProcessor();
    }
  }

  @Test
  void testLLMObsSpanProcessorModifiesRetrievalOutputDocuments() throws Exception {
    LLMObs.registerProcessor(
        span -> {
          span.setOutput(
              Collections.singletonList(LLMObs.LLMMessage.from("", "processed document")));
          return span;
        });

    try {
      CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
      AgentSpan retrievalSpan =
          tracer
              .buildSpan("datadog", "retrieval")
              .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_RETRIEVAL_SPAN_KIND)
              .withTag(
                  "_ml_obs_tag.output",
                  Collections.singletonList(
                      LLMObs.Document.from("original document", "result.txt", "doc-456", 0.9)))
              .start();
      retrievalSpan.setSpanType(InternalSpanTypes.LLMOBS);
      retrievalSpan.finish();

      List<Map<String, Object>> spans =
          serialize(Collections.singletonList((DDSpan) retrievalSpan), new LLMObsSpanMapper());
      Map<String, Object> meta = (Map<String, Object>) spans.get(0).get("meta");
      Map<String, Object> output = (Map<String, Object>) meta.get("output");
      List<Map<String, Object>> documents = (List<Map<String, Object>>) output.get("documents");

      assertEquals("processed document", documents.get(0).get("text"));
      assertEquals("result.txt", documents.get(0).get("name"));
      assertEquals("doc-456", documents.get(0).get("id"));
      assertEquals(0.9, documents.get(0).get("score"));
      tracer.close();
    } finally {
      LLMObs.deregisterProcessor();
    }
  }

  @Test
  void testLLMObsSpanMapperSerializesDocumentIO() throws Exception {
    LLMObsSpanMapper mapper = new LLMObsSpanMapper();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    AgentSpan embeddingSpan =
        tracer
            .buildSpan("datadog", "embedding")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_EMBEDDING_SPAN_KIND)
            .withTag(
                "_ml_obs_tag.input",
                Arrays.asList(
                    LLMObs.Document.from("embedding input", "embedding.txt", "embedding-1", 0.75),
                    LLMObs.Document.from("embedding text only")))
            .start();
    embeddingSpan.setSpanType(InternalSpanTypes.LLMOBS);
    embeddingSpan.finish();

    AgentSpan retrievalSpan =
        tracer
            .buildSpan("datadog", "retrieval")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_RETRIEVAL_SPAN_KIND)
            .withTag(
                "_ml_obs_tag.output",
                Collections.singletonList(
                    LLMObs.Document.from("retrieval output", "retrieval.txt", "retrieval-1", 0.9)))
            .start();
    retrievalSpan.setSpanType(InternalSpanTypes.LLMOBS);
    retrievalSpan.finish();

    List<String> nonDocumentInput = Arrays.asList("first raw input", "second raw input");
    AgentSpan embeddingWithNonDocumentInput =
        tracer
            .buildSpan("datadog", "embedding")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_EMBEDDING_SPAN_KIND)
            .withTag("_ml_obs_tag.input", nonDocumentInput)
            .start();
    embeddingWithNonDocumentInput.setSpanType(InternalSpanTypes.LLMOBS);
    embeddingWithNonDocumentInput.finish();

    List<String> nonDocumentOutput = Arrays.asList("first raw result", "second raw result");
    AgentSpan retrievalWithNonDocumentOutput =
        tracer
            .buildSpan("datadog", "retrieval")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_RETRIEVAL_SPAN_KIND)
            .withTag("_ml_obs_tag.output", nonDocumentOutput)
            .start();
    retrievalWithNonDocumentOutput.setSpanType(InternalSpanTypes.LLMOBS);
    retrievalWithNonDocumentOutput.finish();

    List<DDSpan> trace =
        Arrays.asList(
            (DDSpan) embeddingSpan,
            (DDSpan) retrievalSpan,
            (DDSpan) embeddingWithNonDocumentInput,
            (DDSpan) retrievalWithNonDocumentOutput);
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(16 * 1024, sink));
    packer.format(trace, mapper);
    packer.flush();

    datadog.trace.common.writer.Payload payload = mapper.newPayload();
    payload.withBody(trace.size(), sink.captured);
    Map<String, Object> result = objectMapper.readValue(writeTo(payload), Map.class);
    List<Map<String, Object>> spans = (List<Map<String, Object>>) result.get("spans");

    Map<String, Object> embeddingMeta = (Map<String, Object>) spans.get(0).get("meta");
    Map<String, Object> embeddingInput = (Map<String, Object>) embeddingMeta.get("input");
    List<Map<String, Object>> embeddingDocuments =
        (List<Map<String, Object>>) embeddingInput.get("documents");
    assertEquals("embedding input", embeddingDocuments.get(0).get("text"));
    assertEquals("embedding.txt", embeddingDocuments.get(0).get("name"));
    assertEquals("embedding-1", embeddingDocuments.get(0).get("id"));
    assertEquals(0.75, embeddingDocuments.get(0).get("score"));
    assertEquals("embedding text only", embeddingDocuments.get(1).get("text"));
    assertFalse(embeddingDocuments.get(1).containsKey("name"));
    assertFalse(embeddingDocuments.get(1).containsKey("id"));
    assertFalse(embeddingDocuments.get(1).containsKey("score"));
    assertFalse(embeddingInput.containsKey("value"));

    Map<String, Object> retrievalMeta = (Map<String, Object>) spans.get(1).get("meta");
    Map<String, Object> retrievalOutput = (Map<String, Object>) retrievalMeta.get("output");
    List<Map<String, Object>> retrievalDocuments =
        (List<Map<String, Object>>) retrievalOutput.get("documents");
    assertEquals("retrieval output", retrievalDocuments.get(0).get("text"));
    assertEquals("retrieval.txt", retrievalDocuments.get(0).get("name"));
    assertEquals("retrieval-1", retrievalDocuments.get(0).get("id"));
    assertEquals(0.9, retrievalDocuments.get(0).get("score"));
    assertFalse(retrievalOutput.containsKey("value"));

    Map<String, Object> fallbackEmbeddingMeta = (Map<String, Object>) spans.get(2).get("meta");
    Map<String, Object> fallbackInput = (Map<String, Object>) fallbackEmbeddingMeta.get("input");
    assertEquals(nonDocumentInput, fallbackInput.get("value"));
    assertFalse(fallbackInput.containsKey("documents"));

    Map<String, Object> fallbackRetrievalMeta = (Map<String, Object>) spans.get(3).get("meta");
    Map<String, Object> fallbackOutput = (Map<String, Object>) fallbackRetrievalMeta.get("output");
    assertEquals(nonDocumentOutput, fallbackOutput.get("value"));
    assertFalse(fallbackOutput.containsKey("documents"));

    tracer.close();
  }

  @Test
  void testLLMObsSpanMapperPreservesStringRetrievalOutput() throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    AgentSpan retrievalSpan =
        tracer
            .buildSpan("datadog", "retrieval")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_RETRIEVAL_SPAN_KIND)
            .withTag("_ml_obs_tag.output", "retrieval output")
            .start();
    retrievalSpan.setSpanType(InternalSpanTypes.LLMOBS);
    retrievalSpan.finish();

    List<Map<String, Object>> spans =
        serialize(Collections.singletonList((DDSpan) retrievalSpan), new LLMObsSpanMapper());
    Map<String, Object> meta = (Map<String, Object>) spans.get(0).get("meta");
    Map<String, Object> output = (Map<String, Object>) meta.get("output");

    assertEquals("retrieval output", output.get("value"));
    tracer.close();
  }

  @Test
  void testLLMObsSpanProcessorCanDropSpan() throws Exception {
    LLMObs.registerProcessor(span -> "true".equals(span.getTag("drop")) ? null : span);

    try {
      CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
      AgentSpan dropped = newLlmObsSpan(tracer, "dropped", true);
      AgentSpan retained = newLlmObsSpan(tracer, "retained", false);

      List<Map<String, Object>> spans =
          serialize(Arrays.asList((DDSpan) dropped, (DDSpan) retained), new LLMObsSpanMapper());

      assertEquals(1, spans.size());
      assertEquals("retained", spans.get(0).get("name"));
      tracer.close();
    } finally {
      LLMObs.deregisterProcessor();
    }
  }

  @Test
  void testLLMObsSpanProcessorExceptionDropsSpan() throws Exception {
    LLMObs.registerProcessor(
        span -> {
          if ("true".equals(span.getTag("drop"))) {
            throw new IllegalStateException("processor failure");
          }
          return span;
        });

    try {
      CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
      AgentSpan dropped = newLlmObsSpan(tracer, "dropped", true);
      AgentSpan retained = newLlmObsSpan(tracer, "retained", false);

      List<Map<String, Object>> spans =
          serialize(Arrays.asList((DDSpan) dropped, (DDSpan) retained), new LLMObsSpanMapper());

      assertEquals(1, spans.size());
      assertEquals("retained", spans.get(0).get("name"));
      tracer.close();
    } finally {
      LLMObs.deregisterProcessor();
    }
  }

  @Test
  void testLLMObsSpanProcessorErrorDropsSpan() throws Exception {
    LLMObs.registerProcessor(
        span -> {
          if ("true".equals(span.getTag("drop"))) {
            throw new AssertionError("processor failure");
          }
          return span;
        });

    try {
      CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
      AgentSpan dropped = newLlmObsSpan(tracer, "dropped", true);
      AgentSpan retained = newLlmObsSpan(tracer, "retained", false);

      List<Map<String, Object>> spans =
          serialize(Arrays.asList((DDSpan) dropped, (DDSpan) retained), new LLMObsSpanMapper());

      assertEquals(1, spans.size());
      assertEquals("retained", spans.get(0).get("name"));
      tracer.close();
    } finally {
      LLMObs.deregisterProcessor();
    }
  }

  @Test
  void testLLMObsSpanProcessorRunsOnceWhenSerializationRetries() {
    AtomicInteger calls = new AtomicInteger();
    LLMObsMetricCollector.get().drain();
    LLMObs.registerProcessor(
        span -> {
          calls.incrementAndGet();
          return "true".equals(span.getTag("drop")) ? null : span;
        });

    try {
      CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
      AgentSpan first = newLlmObsSpan(tracer, "first", false);
      AgentSpan dropped = newLlmObsSpan(tracer, "dropped", true);
      AgentSpan retained = newLlmObsSpan(tracer, "retained", false);
      String largeInput = String.join("", Collections.nCopies(600, "x"));
      first.setTag(
          "_ml_obs_tag.input",
          Collections.singletonList(LLMObs.LLMMessage.from("user", largeInput)));
      dropped.setTag(
          "_ml_obs_tag.input",
          Collections.singletonList(LLMObs.LLMMessage.from("user", largeInput)));
      retained.setTag(
          "_ml_obs_tag.input",
          Collections.singletonList(LLMObs.LLMMessage.from("user", largeInput)));

      LLMObsSpanMapper mapper = new LLMObsSpanMapper();
      CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer();
      MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, sink));

      assertTrue(packer.format(Collections.singletonList((DDSpan) first), mapper));
      assertTrue(packer.format(Arrays.asList((DDSpan) dropped, (DDSpan) retained), mapper));
      assertEquals(1, sink.accepts);
      assertEquals(3, calls.get());
      assertEquals(
          3,
          LLMObsMetricCollector.get().drain().stream()
              .filter(
                  metric ->
                      LLMObsMetricCollector.USER_PROCESSOR_CALLED_METRIC.equals(metric.metricName))
              .count());
      tracer.close();
    } finally {
      LLMObs.deregisterProcessor();
      LLMObsMetricCollector.get().drain();
    }
  }

  @Test
  void testLLMObsSpanProcessorInputAndOutputRejectNull() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    LLMObsSpanDataAdapter adapter =
        new LLMObsSpanDataAdapter((DDSpan) newLlmObsSpan(tracer, "processed", false));

    assertThrows(NullPointerException.class, () -> adapter.setInput(null));
    assertThrows(NullPointerException.class, () -> adapter.setOutput(null));
    tracer.close();
  }

  private static AgentSpan newLlmObsSpan(CoreTracer tracer, String name, boolean drop) {
    AgentSpan span =
        tracer
            .buildSpan("datadog", name)
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
            .withTag("_ml_obs_tag.drop", drop)
            .start();
    span.setSpanType(InternalSpanTypes.LLMOBS);
    span.finish();
    return span;
  }

  private static List<Map<String, Object>> serialize(List<DDSpan> trace, LLMObsSpanMapper mapper)
      throws Exception {
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(16 * 1024, sink));

    packer.format(trace, mapper);
    packer.flush();

    assertNotNull(sink.captured);
    datadog.trace.common.writer.Payload payload = mapper.newPayload();
    payload.withBody(trace.size(), sink.captured);
    Map<String, Object> result = objectMapper.readValue(writeTo(payload), Map.class);
    return (List<Map<String, Object>>) result.get("spans");
  }

  private static byte[] writeTo(datadog.trace.common.writer.Payload payload) throws IOException {
    ByteArrayOutputStream channel = new ByteArrayOutputStream();
    payload.writeTo(
        new WritableByteChannel() {
          @Override
          public int write(ByteBuffer src) throws IOException {
            byte[] bytes = new byte[src.remaining()];
            src.get(bytes);
            channel.write(bytes);
            return bytes.length;
          }

          @Override
          public boolean isOpen() {
            return true;
          }

          @Override
          public void close() throws IOException {}
        });
    return channel.toByteArray();
  }

  private static Map<String, Object> serializeSingleSpan(LLMObsSpanMapper mapper, AgentSpan span)
      throws IOException {
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(16 * 1024, sink));
    packer.format(Collections.singletonList((DDSpan) span), mapper);
    packer.flush();

    assertNotNull(sink.captured);
    datadog.trace.common.writer.Payload payload = mapper.newPayload();
    payload.withBody(1, sink.captured);
    Map<String, Object> result = objectMapper.readValue(writeTo(payload), Map.class);
    List<Map<String, Object>> spans = (List<Map<String, Object>>) result.get("spans");
    return spans.get(0);
  }

  // The _dd sub-map is hand-sized msgpack, so an off-by-one corrupts the stream rather than failing
  // loudly. These three cases pin the decoded map for every combination the writer can produce.

  @Test
  void testSamplingFieldsDefaultToRetainWhenTheSpanCarriesNoDecision() throws Exception {
    LLMObsSpanMapper mapper = new LLMObsSpanMapper();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    // DDLLMObsSpan stamps a decision on every span, so this shape should not occur in practice;
    // the mapper still has to emit a well-formed _dd map if it ever does.
    AgentSpan span =
        tracer
            .buildSpan("datadog", "chat-completion")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
            .start();
    span.setSpanType(InternalSpanTypes.LLMOBS);
    span.finish();

    Map<String, Object> spanData = serializeSingleSpan(mapper, span);
    Map<String, Object> dd = (Map<String, Object>) spanData.get("_dd");
    assertEquals(5, dd.size());
    assertEquals("1", dd.get("sampling_decision"));
    assertEquals("1", dd.get("sample_rate"));

    tracer.close();
  }

  @Test
  void testSamplingFieldsEmittedForRetainedSpanWithSessionId() throws Exception {
    LLMObsSpanMapper mapper = new LLMObsSpanMapper();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    AgentSpan span =
        tracer
            .buildSpan("datadog", "chat-completion")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
            .withTag("_ml_obs_tag.session_id", "abc-123-session")
            .withTag("_ml_obs_tag.sampling_decision", "1")
            .withTag("_ml_obs_tag.sample_rate", "0.5")
            .start();
    span.setSpanType(InternalSpanTypes.LLMOBS);
    span.finish();

    Map<String, Object> spanData = serializeSingleSpan(mapper, span);
    Map<String, Object> dd = (Map<String, Object>) spanData.get("_dd");
    assertEquals(5, dd.size());
    assertEquals("1", dd.get("sampling_decision"));
    assertEquals("0.5", dd.get("sample_rate"));
    // The session_id field must still land alongside the sampling fields.
    assertEquals("abc-123-session", spanData.get("session_id"));

    // Internal fields must not leak into the user-visible tags[] array.
    List<String> tags = (List<String>) spanData.get("tags");
    assertFalse(tags.stream().anyMatch(tag -> tag.startsWith("sampling_decision:")));
    assertFalse(tags.stream().anyMatch(tag -> tag.startsWith("sample_rate:")));

    tracer.close();
  }

  @Test
  void testSamplingFieldsEmittedForDroppedSpanWithoutSessionId() throws Exception {
    LLMObsSpanMapper mapper = new LLMObsSpanMapper();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    AgentSpan span =
        tracer
            .buildSpan("datadog", "chat-completion")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
            .withTag("_ml_obs_tag.sampling_decision", "0")
            .withTag("_ml_obs_tag.sample_rate", "0.1")
            .start();
    span.setSpanType(InternalSpanTypes.LLMOBS);
    span.finish();

    Map<String, Object> spanData = serializeSingleSpan(mapper, span);
    Map<String, Object> dd = (Map<String, Object>) spanData.get("_dd");
    assertEquals(5, dd.size());
    assertEquals("0", dd.get("sampling_decision"));
    assertEquals("0.1", dd.get("sample_rate"));
    assertFalse(spanData.containsKey("session_id"));

    tracer.close();
  }

  static class CapturingByteBufferConsumer implements ByteBufferConsumer {

    ByteBuffer captured;
    int accepts;

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      captured = buffer;
      accepts++;
    }
  }
}
