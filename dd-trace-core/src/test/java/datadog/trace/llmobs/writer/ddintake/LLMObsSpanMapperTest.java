package datadog.trace.llmobs.writer.ddintake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.test.DDCoreSpecification;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.msgpack.jackson.dataformat.MessagePackFactory;

class LLMObsSpanMapperTest extends DDCoreSpecification {

  private ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory());

  @Test
  void testLLMObsSpanMapperSerialization() throws Exception {
    LLMObsSpanMapper mapper = new LLMObsSpanMapper();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    AgentSpan llmSpan =
        tracer
            .buildSpan("openai.request")
            .withResourceName("createCompletion")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
            .withTag("_ml_obs_tag.model_name", "gpt-4")
            .withTag("_ml_obs_tag.model_provider", "openai")
            .withTag("_ml_obs_metric.input_tokens", 50)
            .withTag("_ml_obs_metric.output_tokens", 25)
            .withTag("_ml_obs_metric.total_tokens", 75)
            .start();

    llmSpan.setSpanType(InternalSpanTypes.LLMOBS);

    List<LLMObs.LLMMessage> inputMessages =
        Collections.singletonList(
            LLMObs.LLMMessage.from("user", "Hello, what's the weather like?"));
    List<LLMObs.LLMMessage> outputMessages =
        Collections.singletonList(
            LLMObs.LLMMessage.from("assistant", "I'll help you check the weather."));
    llmSpan.setTag("_ml_obs_tag.input", inputMessages);
    llmSpan.setTag("_ml_obs_tag.output", outputMessages);
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("temperature", 0.7);
    metadata.put("max_tokens", 100);
    llmSpan.setTag("_ml_obs_tag.metadata", metadata);

    llmSpan.finish();

    List<AgentSpan> trace = Collections.singletonList(llmSpan);
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, sink));

    packer.format((List) trace, mapper);
    packer.flush();

    assertNotNull(sink.captured);
    datadog.trace.common.writer.Payload payload = mapper.newPayload();
    payload.withBody(1, sink.captured);

    int sizeInBytes = payload.sizeInBytes();

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

    byte[] bytesWritten = channel.toByteArray();
    assertEquals(sizeInBytes, bytesWritten.length);
    Map result = objectMapper.readValue(bytesWritten, Map.class);

    assertTrue(result.containsKey("event_type"));
    assertEquals("span", result.get("event_type"));
    assertTrue(result.containsKey("_dd.stage"));
    assertEquals("raw", result.get("_dd.stage"));
    assertTrue(result.containsKey("spans"));
    assertTrue(result.get("spans") instanceof List);
    assertEquals(1, ((List) result.get("spans")).size());

    Map spanData = (Map) ((List) result.get("spans")).get(0);
    assertEquals("OpenAI.createCompletion", spanData.get("name"));
    assertTrue(spanData.containsKey("span_id"));
    assertTrue(spanData.containsKey("trace_id"));
    assertTrue(spanData.containsKey("start_ns"));
    assertTrue(spanData.containsKey("duration"));
    assertEquals(0, spanData.get("error"));

    assertTrue(spanData.containsKey("meta"));
    Map meta = (Map) spanData.get("meta");
    assertEquals("llm", meta.get("span.kind"));
    assertTrue(meta.containsKey("input"));
    Map input = (Map) meta.get("input");
    assertTrue(input.containsKey("messages"));
    List inputMsgs = (List) input.get("messages");
    Map inputMsg0 = (Map) inputMsgs.get(0);
    assertTrue(inputMsg0.containsKey("content"));
    assertEquals("Hello, what's the weather like?", inputMsg0.get("content"));
    assertTrue(inputMsg0.containsKey("role"));
    assertEquals("user", inputMsg0.get("role"));

    assertTrue(meta.containsKey("output"));
    Map output = (Map) meta.get("output");
    assertTrue(output.containsKey("messages"));
    List outputMsgs = (List) output.get("messages");
    Map outputMsg0 = (Map) outputMsgs.get(0);
    assertEquals("I'll help you check the weather.", outputMsg0.get("content"));
    assertEquals("assistant", outputMsg0.get("role"));
    assertTrue(meta.containsKey("metadata"));

    assertTrue(spanData.containsKey("metrics"));
    Map metrics = (Map) spanData.get("metrics");
    assertEquals(50.0, ((Number) metrics.get("input_tokens")).doubleValue(), 0.001);
    assertEquals(25.0, ((Number) metrics.get("output_tokens")).doubleValue(), 0.001);
    assertEquals(75.0, ((Number) metrics.get("total_tokens")).doubleValue(), 0.001);

    assertTrue(spanData.containsKey("tags"));
    List tags = (List) spanData.get("tags");
    assertTrue(tags.contains("language:jvm"));
  }

  @Test
  void testLLMObsSpanMapperWritesNoSpansWhenNoneAreLLMObsSpans() throws Exception {
    LLMObsSpanMapper mapper = new LLMObsSpanMapper();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    AgentSpan regularSpan1 =
        tracer
            .buildSpan("http.request")
            .withResourceName("GET /api/users")
            .withTag("http.method", "GET")
            .withTag("http.url", "https://example.com/api/users")
            .start();
    regularSpan1.finish();

    AgentSpan regularSpan2 =
        tracer
            .buildSpan("database.query")
            .withResourceName("SELECT * FROM users")
            .withTag("db.type", "postgresql")
            .start();
    regularSpan2.finish();

    List<AgentSpan> trace = Arrays.asList(regularSpan1, regularSpan2);
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, sink));

    packer.format((List) trace, mapper);
    packer.flush();

    assertNull(sink.captured);
  }

  @Test
  void testConsecutivePackerFormatCallsAccumulateSpansFromMultipleTraces() throws Exception {
    LLMObsSpanMapper mapper = new LLMObsSpanMapper();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    AgentSpan llmSpan1 =
        tracer
            .buildSpan("chat-completion-1")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
            .withTag("_ml_obs_tag.model_name", "gpt-4")
            .withTag("_ml_obs_tag.model_provider", "openai")
            .start();
    llmSpan1.setSpanType(InternalSpanTypes.LLMOBS);
    llmSpan1.finish();

    AgentSpan llmSpan2 =
        tracer
            .buildSpan("chat-completion-2")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
            .withTag("_ml_obs_tag.model_name", "gpt-3.5")
            .withTag("_ml_obs_tag.model_provider", "openai")
            .start();
    llmSpan2.setSpanType(InternalSpanTypes.LLMOBS);
    llmSpan2.finish();

    AgentSpan llmSpan3 =
        tracer
            .buildSpan("chat-completion-3")
            .withTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND)
            .withTag("_ml_obs_tag.model_name", "claude-3")
            .withTag("_ml_obs_tag.model_provider", "anthropic")
            .start();
    llmSpan3.setSpanType(InternalSpanTypes.LLMOBS);
    llmSpan3.finish();

    List<AgentSpan> trace1 = Arrays.asList(llmSpan1, llmSpan2);
    List<AgentSpan> trace2 = Collections.singletonList(llmSpan3);
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, sink));

    packer.format((List) trace1, mapper);
    packer.format((List) trace2, mapper);
    packer.flush();

    assertNotNull(sink.captured);
    datadog.trace.common.writer.Payload payload = mapper.newPayload();
    payload.withBody(3, sink.captured);

    int sizeInBytes = payload.sizeInBytes();

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

    byte[] bytesWritten = channel.toByteArray();
    assertEquals(sizeInBytes, bytesWritten.length);
    Map result = objectMapper.readValue(bytesWritten, Map.class);

    assertEquals("span", result.get("event_type"));
    assertEquals("raw", result.get("_dd.stage"));
    List spans = (List) result.get("spans");
    assertEquals(3, spans.size());

    List<String> spanNames = new ArrayList<>();
    for (Object s : spans) {
      spanNames.add((String) ((Map) s).get("name"));
    }
    assertTrue(spanNames.contains("chat-completion-1"));
    assertTrue(spanNames.contains("chat-completion-2"));
    assertTrue(spanNames.contains("chat-completion-3"));
  }

  static class CapturingByteBufferConsumer implements ByteBufferConsumer {
    ByteBuffer captured;

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      captured = buffer;
    }
  }
}
