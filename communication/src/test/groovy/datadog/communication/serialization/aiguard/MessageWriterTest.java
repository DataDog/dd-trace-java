package datadog.communication.serialization.aiguard;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import datadog.communication.serialization.EncodingCache;
import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.aiguard.AIGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;

public class MessageWriterTest {

  private EncodingCache encodingCache;
  private GrowableBuffer buffer;
  private MsgPackWriter writer;

  @BeforeEach
  public void setup() {
    final HashMap<CharSequence, byte[]> cache = new HashMap<>();
    encodingCache =
        string -> cache.computeIfAbsent(string, s -> s.toString().getBytes(StandardCharsets.UTF_8));
    buffer = new GrowableBuffer(1024);
    writer = new MsgPackWriter(buffer);
  }

  @Test
  public void testWriteMessage() throws IOException {
    final AIGuard.Message message = AIGuard.Message.message("user", "What day is today?");

    writer.writeObject(message, encodingCache);

    try (final MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer.slice())) {
      final Map<String, String> value = asStringValueMap(unpacker.unpackValue().asMapValue());
      assertThat(value.size(), equalTo(2));
      assertThat(value.get("role"), equalTo("user"));
      assertThat(value.get("content"), equalTo("What day is today?"));
    }
  }

  @Test
  public void testWriteToolCall() throws IOException {
    final AIGuard.Message message =
        AIGuard.Message.assistant(
            AIGuard.ToolCall.toolCall("call_1", "function_1", "args_1"),
            AIGuard.ToolCall.toolCall("call_2", "function_2", "args_2"));

    writer.writeObject(message, encodingCache);

    try (final MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer.slice())) {
      final Map<String, Value> value = asStringKeyMap(unpacker.unpackValue());
      assertThat(value.size(), equalTo(2));
      assertThat(asString(value.get("role")), equalTo("assistant"));

      final List<Value> toolCalls = value.get("tool_calls").asArrayValue().list();
      assertThat(toolCalls, hasSize(2));

      final Map<String, Value> firstCall = asStringKeyMap(toolCalls.get(0));
      assertThat(asString(firstCall.get("id")), equalTo("call_1"));
      final Map<String, String> firstFunction = asStringValueMap(firstCall.get("function"));
      assertThat(firstFunction.get("name"), equalTo("function_1"));
      assertThat(firstFunction.get("arguments"), equalTo("args_1"));

      final Map<String, Value> secondCall = asStringKeyMap(toolCalls.get(1));
      assertThat(asString(secondCall.get("id")), equalTo("call_2"));
      final Map<String, String> secondFunction = asStringValueMap(secondCall.get("function"));
      assertThat(secondFunction.get("name"), equalTo("function_2"));
      assertThat(secondFunction.get("arguments"), equalTo("args_2"));
    }
  }

  @Test
  public void testWriteToolOutput() throws IOException {
    final AIGuard.Message message = AIGuard.Message.tool("call_1", "output");

    writer.writeObject(message, encodingCache);

    try (final MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer.slice())) {
      final Map<String, Value> value = asStringKeyMap(unpacker.unpackValue());
      assertThat(value.size(), equalTo(3));
      assertThat(asString(value.get("role")), equalTo("tool"));
      assertThat(asString(value.get("tool_call_id")), equalTo("call_1"));
      assertThat(asString(value.get("content")), equalTo("output"));
    }
  }

  private <K, V> Map<K, V> mapValue(
      final Value values,
      final Function<Value, K> keyMapper,
      final Function<Value, V> valueMapper) {
    return values.asMapValue().entrySet().stream()
        .collect(
            Collectors.toMap(
                entry -> keyMapper.apply(entry.getKey()),
                entry -> valueMapper.apply(entry.getValue())));
  }

  private Map<String, Value> asStringKeyMap(final Value values) {
    return mapValue(values, this::asString, Function.identity());
  }

  private Map<String, String> asStringValueMap(final Value values) {
    return mapValue(values, this::asString, this::asString);
  }

  private String asString(final Value value) {
    return value.asStringValue().asString();
  }
}
