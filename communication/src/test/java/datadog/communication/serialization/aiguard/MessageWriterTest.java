package datadog.communication.serialization.aiguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.serialization.EncodingCache;
import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.aiguard.AIGuard;
import datadog.trace.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;

@WithConfig(key = "ai_guard.enabled", value = "true")
class MessageWriterTest extends DDJavaSpecification {

  private EncodingCache encodingCache;
  private GrowableBuffer buffer;
  private MsgPackWriter writer;

  @BeforeEach
  void setup() {
    HashMap<CharSequence, byte[]> cache = new HashMap<>();
    encodingCache =
        chars -> cache.computeIfAbsent(chars, s -> s.toString().getBytes(StandardCharsets.UTF_8));
    buffer = new GrowableBuffer(1024);
    writer = new MsgPackWriter(buffer);
  }

  @Test
  void testWriteMessage() throws IOException {
    AIGuard.Message message = AIGuard.Message.message("user", "What day is today?");

    writer.writeObject(message, encodingCache);

    try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer.slice())) {
      Map<String, String> value = asStringValueMap(unpacker.unpackValue());
      assertEquals(2, value.size());
      assertEquals("user", value.get("role"));
      assertEquals("What day is today?", value.get("content"));
    }
  }

  @Test
  void testWriteToolCall() throws IOException {
    AIGuard.Message message =
        AIGuard.Message.assistant(
            AIGuard.ToolCall.toolCall("call_1", "function_1", "args_1"),
            AIGuard.ToolCall.toolCall("call_2", "function_2", "args_2"));

    writer.writeObject(message, encodingCache);

    try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer.slice())) {
      Map<String, Value> value = asStringKeyMap(unpacker.unpackValue());
      assertEquals(2, value.size());
      assertEquals("assistant", asString(value.get("role")));

      List<Value> toolCalls = value.get("tool_calls").asArrayValue().list();
      assertEquals(2, toolCalls.size());

      Map<String, Value> firstCall = asStringKeyMap(toolCalls.get(0));
      assertEquals("call_1", asString(firstCall.get("id")));
      Map<String, String> firstFunction = asStringValueMap(firstCall.get("function"));
      assertEquals("function_1", firstFunction.get("name"));
      assertEquals("args_1", firstFunction.get("arguments"));

      Map<String, Value> secondCall = asStringKeyMap(toolCalls.get(1));
      assertEquals("call_2", asString(secondCall.get("id")));
      Map<String, String> secondFunction = asStringValueMap(secondCall.get("function"));
      assertEquals("function_2", secondFunction.get("name"));
      assertEquals("args_2", secondFunction.get("arguments"));
    }
  }

  @Test
  void testWriteToolOutput() throws IOException {
    AIGuard.Message message = AIGuard.Message.tool("call_1", "output");

    writer.writeObject(message, encodingCache);

    try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer.slice())) {
      Map<String, String> value = asStringValueMap(unpacker.unpackValue());
      assertEquals(3, value.size());
      assertEquals("tool", value.get("role"));
      assertEquals("call_1", value.get("tool_call_id"));
      assertEquals("output", value.get("content"));
    }
  }

  @Test
  void testWriteMessageWithTextContentParts() throws IOException {
    AIGuard.Message message =
        AIGuard.Message.message(
            "user", Collections.singletonList(AIGuard.ContentPart.text("Hello world")));

    writer.writeObject(message, encodingCache);

    try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer.slice())) {
      Map<String, Value> value = asStringKeyMap(unpacker.unpackValue());
      assertEquals(2, value.size());
      assertEquals("user", asString(value.get("role")));

      List<Value> contentParts = value.get("content").asArrayValue().list();
      assertEquals(1, contentParts.size());

      Map<String, Value> part = asStringKeyMap(contentParts.get(0));
      assertEquals("text", asString(part.get("type")));
      assertEquals("Hello world", asString(part.get("text")));
    }
  }

  @Test
  void testWriteMessageWithImageUrlContentParts() throws IOException {
    AIGuard.Message message =
        AIGuard.Message.message(
            "user",
            Collections.singletonList(
                AIGuard.ContentPart.imageUrl("https://example.com/image.jpg")));

    writer.writeObject(message, encodingCache);

    try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer.slice())) {
      Map<String, Value> value = asStringKeyMap(unpacker.unpackValue());
      assertEquals(2, value.size());
      assertEquals("user", asString(value.get("role")));

      List<Value> contentParts = value.get("content").asArrayValue().list();
      assertEquals(1, contentParts.size());

      Map<String, Value> part = asStringKeyMap(contentParts.get(0));
      assertEquals("image_url", asString(part.get("type")));

      Map<String, Value> imageUrl = asStringKeyMap(part.get("image_url"));
      assertEquals("https://example.com/image.jpg", asString(imageUrl.get("url")));
    }
  }

  @Test
  void testWriteMessageWithMixedContentParts() throws IOException {
    AIGuard.Message message =
        AIGuard.Message.message(
            "user",
            Arrays.asList(
                AIGuard.ContentPart.text("Describe this:"),
                AIGuard.ContentPart.imageUrl("https://example.com/image.jpg"),
                AIGuard.ContentPart.text("What is it?")));

    writer.writeObject(message, encodingCache);

    try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer.slice())) {
      Map<String, Value> value = asStringKeyMap(unpacker.unpackValue());
      assertEquals(2, value.size());
      assertEquals("user", asString(value.get("role")));

      List<Value> contentParts = value.get("content").asArrayValue().list();
      assertEquals(3, contentParts.size());

      Map<String, Value> part1 = asStringKeyMap(contentParts.get(0));
      assertEquals("text", asString(part1.get("type")));
      assertEquals("Describe this:", asString(part1.get("text")));

      Map<String, Value> part2 = asStringKeyMap(contentParts.get(1));
      assertEquals("image_url", asString(part2.get("type")));
      Map<String, Value> imageUrl = asStringKeyMap(part2.get("image_url"));
      assertEquals("https://example.com/image.jpg", asString(imageUrl.get("url")));

      Map<String, Value> part3 = asStringKeyMap(contentParts.get(2));
      assertEquals("text", asString(part3.get("type")));
      assertEquals("What is it?", asString(part3.get("text")));
    }
  }

  @Test
  void testContentPartsTypeSerializesAsStringNotInteger() throws IOException {
    AIGuard.Message message =
        AIGuard.Message.message(
            "user", Collections.singletonList(AIGuard.ContentPart.text("Test")));

    writer.writeObject(message, encodingCache);

    try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer.slice())) {
      Map<String, Value> value = asStringKeyMap(unpacker.unpackValue());
      List<Value> contentParts = value.get("content").asArrayValue().list();
      Map<String, Value> part = asStringKeyMap(contentParts.get(0));

      assertTrue(part.get("type").isStringValue());
      assertFalse(part.get("type").isIntegerValue());
      assertEquals("text", asString(part.get("type")));
    }
  }

  @Test
  void testBackwardCompatibilityWithStringContent() throws IOException {
    AIGuard.Message message = AIGuard.Message.message("user", "Plain text message");

    writer.writeObject(message, encodingCache);

    try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer.slice())) {
      Map<String, String> value = asStringValueMap(unpacker.unpackValue());
      assertEquals(2, value.size());
      assertEquals("user", value.get("role"));
      assertEquals("Plain text message", value.get("content"));
    }
  }

  private static <K, V> Map<K, V> mapValue(
      Value values, Function<Value, K> keyMapper, Function<Value, V> valueMapper) {
    Map<K, V> result = new LinkedHashMap<>();
    for (Map.Entry<Value, Value> entry : values.asMapValue().entrySet()) {
      result.put(keyMapper.apply(entry.getKey()), valueMapper.apply(entry.getValue()));
    }
    return result;
  }

  private static Map<String, Value> asStringKeyMap(Value values) {
    return mapValue(values, MessageWriterTest::asString, Function.identity());
  }

  private static Map<String, String> asStringValueMap(Value values) {
    return mapValue(values, MessageWriterTest::asString, MessageWriterTest::asString);
  }

  private static String asString(Value value) {
    return value.asStringValue().asString();
  }
}
