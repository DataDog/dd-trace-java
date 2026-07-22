package datadog.trace.llmobs.writer.ddintake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.DDTags;
import datadog.trace.api.llmobs.LLMObs;
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

    Map<String, Object> inputMap = new LinkedHashMap<>();
    inputMap.put("messages", inputMessages);
    inputMap.put("prompt", prompt);
    llmSpan.setTag("_ml_obs_tag.input", inputMap);
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
    Map<String, Object> toolDef = new LinkedHashMap<>();
    toolDef.put("name", "get_weather");
    toolDef.put("description", "Get weather by city");
    toolDef.put("schema", schema);
    llmSpan.setTag("_ml_obs_tag.tool_definitions", Collections.singletonList(toolDef));

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
    List<Map<String, Object>> outputMsgs = (List<Map<String, Object>>) outputResult.get("messages");
    assertTrue(outputMsgs.get(0).containsKey("content"));
    assertEquals("I'll help you check the weather.", outputMsgs.get(0).get("content"));
    assertTrue(outputMsgs.get(0).containsKey("role"));
    assertEquals("assistant", outputMsgs.get(0).get("role"));
    List<Map<String, Object>> toolDefsResult =
        (List<Map<String, Object>>) meta.get("tool_definitions");
    assertEquals("get_weather", toolDefsResult.get(0).get("name"));
    assertEquals("Get weather by city", toolDefsResult.get(0).get("description"));
    assertEquals(schema, toolDefsResult.get(0).get("schema"));
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

  static class CapturingByteBufferConsumer implements ByteBufferConsumer {

    ByteBuffer captured;

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      captured = buffer;
    }
  }
}
