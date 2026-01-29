package datadog.trace.api.aiguard

import spock.lang.Specification

import static datadog.trace.api.aiguard.AIGuard.Action.ALLOW


class AIGuardTest extends Specification {

  void 'test text message'() {
    when:
    final message = AIGuard.Message.message('user', 'What day is today?')

    then:
    message.role == 'user'
    message.content == 'What day is today?'
    message.toolCallId == null
    message.toolCalls == null
  }

  void 'test assistant tool call'() {
    when:
    final message = AIGuard.Message.assistant(
      AIGuard.ToolCall.toolCall('1', 'execute_http_request', '{ "url": "http://localhost" }'),
      AIGuard.ToolCall.toolCall('2', 'random_number', '{ "min": 0, "max": 10 }')
      )

    then:
    message.role == 'assistant'
    message.content == null
    message.toolCallId == null
    message.toolCalls.size() == 2

    final http = message.toolCalls[0]
    http.id == '1'
    http.function.name == 'execute_http_request'
    http.function.arguments == '{ "url": "http://localhost" }'

    final random = message.toolCalls[1]
    random.id == '2'
    random.function.name == 'random_number'
    random.function.arguments == '{ "min": 0, "max": 10 }'
  }

  void 'test tool'() {
    when:
    final message = AIGuard.Message.tool('2', '5')

    then:
    message.role == 'tool'
    message.content == '5'
    message.toolCallId == '2'
    message.toolCalls == null
  }

  void 'test noop implementation'() {
    when:
    final eval = AIGuard.evaluate([
      AIGuard.Message.message('system', 'You are a beautiful AI assistant'),
      AIGuard.Message.message('user', 'What day is today?'),
      AIGuard.Message.message('assistant', 'Today is monday'),
      AIGuard.Message.message('user', 'Give me a random number'),
      AIGuard.Message.assistant(AIGuard.ToolCall.toolCall('1', 'generate_random_number', '{ "min": 0, "max": 10 }')),
      AIGuard.Message.tool('1', '5'),
      AIGuard.Message.message('assistant', 'Your number is 5')
    ])

    then:
    eval.action == ALLOW
    eval.reason == 'AI Guard is not enabled'
  }

  void 'test ContentPart.text factory'() {
    when:
    final part = AIGuard.ContentPart.text('Hello world')

    then:
    part.type == AIGuard.ContentPart.Type.TEXT
    part.text == 'Hello world'
    part.imageUrl == null
  }

  void 'test ContentPart.imageUrl from String factory'() {
    when:
    final part = AIGuard.ContentPart.imageUrl('https://example.com/image.jpg')

    then:
    part.type == AIGuard.ContentPart.Type.IMAGE_URL
    part.text == null
    part.imageUrl != null
    part.imageUrl.url == 'https://example.com/image.jpg'
  }

  void 'test Message with contentParts'() {
    when:
    final message = AIGuard.Message.message('user', [
      AIGuard.ContentPart.text('Describe this image:'),
      AIGuard.ContentPart.imageUrl('https://example.com/image.jpg')
    ])

    then:
    message.role == 'user'
    message.content == null
    message.contentParts != null
    message.contentParts.size() == 2
    message.contentParts[0].type == AIGuard.ContentPart.Type.TEXT
    message.contentParts[0].text == 'Describe this image:'
    message.contentParts[1].type == AIGuard.ContentPart.Type.IMAGE_URL
    message.contentParts[1].imageUrl.url == 'https://example.com/image.jpg'
  }

  void 'test Message with plain content returns null contentParts'() {
    when:
    final message = AIGuard.Message.message('user', 'Hello')

    then:
    message.content == 'Hello'
    message.contentParts == null
  }

  void 'test Message with contentParts returns null content'() {
    when:
    final message = AIGuard.Message.message('user', [AIGuard.ContentPart.text('Hello')])

    then:
    message.content == null
    message.contentParts != null
  }

  void 'test Message validation allows null content for assistant with tool calls'() {
    when:
    final message = AIGuard.Message.assistant(
      AIGuard.ToolCall.toolCall('1', 'test', '{}')
      )

    then:
    message.role == 'assistant'
    message.content == null
    message.contentParts == null
    message.toolCalls != null
  }

  void 'test Message allows empty contentParts list'() {
    when:
    def message = new AIGuard.Message('user', [], null, null)

    then:
    message.contentParts != null
    message.contentParts.isEmpty()
  }
}
