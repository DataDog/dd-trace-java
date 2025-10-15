package datadog.communication.serialization.aiguard

import datadog.communication.serialization.EncodingCache
import datadog.communication.serialization.GrowableBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.api.aiguard.AIGuard
import datadog.trace.test.util.DDSpecification
import org.msgpack.core.MessagePack
import org.msgpack.value.Value

import java.nio.charset.StandardCharsets
import java.util.function.Function

class MessageWriterTest extends DDSpecification {

  private EncodingCache encodingCache
  private GrowableBuffer buffer
  private MsgPackWriter writer

  void setup() {
    injectSysConfig('ai_guard.enabled', 'true')
    final HashMap<CharSequence, byte[]> cache = new HashMap<>()
    encodingCache = new EncodingCache() {
      @Override
      byte[] encode(CharSequence chars) {
        cache.computeIfAbsent(chars, s -> s.toString().getBytes(StandardCharsets.UTF_8))
      }
    }
    buffer = new GrowableBuffer(1024)
    writer = new MsgPackWriter(buffer)
  }

  void 'test write message'() {
    given:
    final message = AIGuard.Message.message('user', 'What day is today?')

    when:
    writer.writeObject(message, encodingCache)

    then:
    try (final unpacker = MessagePack.newDefaultUnpacker(buffer.slice())) {
      final value = asStringValueMap(unpacker.unpackValue())
      value.size() == 2
      value.role == 'user'
      value.content == 'What day is today?'
    }
  }

  void 'test write tool call'()  {
    given:
    final message =
    AIGuard.Message.assistant(
    AIGuard.ToolCall.toolCall('call_1', 'function_1', 'args_1'),
    AIGuard.ToolCall.toolCall('call_2', 'function_2', 'args_2'))

    when:
    writer.writeObject(message, encodingCache)

    then:
    try (final unpacker = MessagePack.newDefaultUnpacker(buffer.slice())) {
      final value = asStringKeyMap(unpacker.unpackValue())
      value.size() == 2
      asString(value.role) == 'assistant'

      final toolCalls = value.get('tool_calls').asArrayValue().list()
      toolCalls.size() == 2

      final firstCall = asStringKeyMap(toolCalls[0])
      asString(firstCall.id) == 'call_1'
      final firstFunction = asStringValueMap(firstCall.function)
      firstFunction.name == 'function_1'
      firstFunction.arguments == 'args_1'

      final secondCall = asStringKeyMap(toolCalls[1])
      asString(secondCall.id) == 'call_2'
      final secondFunction = asStringValueMap(secondCall.function)
      secondFunction.name == 'function_2'
      secondFunction.arguments == 'args_2'
    }
  }

  void 'test write tool output'() throws IOException {
    given:
    final message = AIGuard.Message.tool('call_1', 'output')

    when:
    writer.writeObject(message, encodingCache)

    then:
    try (final unpacker = MessagePack.newDefaultUnpacker(buffer.slice())) {
      final value = asStringValueMap(unpacker.unpackValue())
      value.size() == 3
      value.role == 'tool'
      value.tool_call_id == 'call_1'
      value.content == 'output'
    }
  }

  private static <K, V> Map<K, V> mapValue(
  final Value values,
  final Function<Value, K> keyMapper,
  final Function<Value, V> valueMapper) {
    return values.asMapValue().entrySet().collectEntries {
      [(keyMapper.apply(it.key)):  valueMapper.apply(it.value)]
    }
  }

  private static Map<String, Value> asStringKeyMap(final Value values) {
    return mapValue(values, MessageWriterTest::asString, Function.identity())
  }

  private static Map<String, String> asStringValueMap(final Value values) {
    return mapValue(values, MessageWriterTest::asString, MessageWriterTest::asString)
  }

  private static String asString(final Value value) {
    return value.asStringValue().asString()
  }
}
