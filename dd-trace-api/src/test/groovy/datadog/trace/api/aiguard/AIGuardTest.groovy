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
}
