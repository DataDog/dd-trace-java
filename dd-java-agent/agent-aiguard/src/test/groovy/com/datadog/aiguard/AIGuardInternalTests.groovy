package com.datadog.aiguard

import static datadog.trace.api.aiguard.AIGuard.Action.ABORT
import static datadog.trace.api.aiguard.AIGuard.Action.ALLOW
import static datadog.trace.api.aiguard.AIGuard.Action.DENY
import static org.codehaus.groovy.runtime.DefaultGroovyMethods.combinations
import static org.mockserver.integration.ClientAndServer.startClientAndServer

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.squareup.moshi.Moshi
import datadog.common.version.VersionInfo
import datadog.http.client.HttpClient
import datadog.http.client.HttpUrl
import datadog.trace.api.Config
import datadog.trace.api.DDTags
import datadog.trace.api.aiguard.AIGuard
import datadog.trace.api.telemetry.WafMetricCollector
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpResponse
import org.mockserver.model.JsonBody
import org.mockserver.model.MediaType
import org.mockserver.verify.VerificationTimes
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import spock.lang.Shared

class AIGuardInternalTests extends DDSpecification {

  @Shared
  protected static final HEADERS = ['DD-API-KEY': 'api',
    'DD-APPLICATION-KEY': 'app',
    'DD-AI-GUARD-VERSION': VersionInfo.VERSION,
    'DD-AI-GUARD-SOURCE': 'SDK',
    'DD-AI-GUARD-LANGUAGE': 'jvm']

  @Shared
  protected static final ORIGINAL_TRACER = AgentTracer.get()

  @Shared
  protected static final MOSHI = new Moshi.Builder().build()

  @Shared
  protected static final MAPPER = new ObjectMapper()
  .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
  .setDefaultPropertyInclusion(
  JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL)
  )

  @Shared
  protected static final TOOL_CALL = [
    AIGuard.Message.message('system', 'You are a beautiful AI assistant'),
    AIGuard.Message.message('user', 'What is 2 + 2'),
    AIGuard.Message.assistant(
    AIGuard.ToolCall.toolCall('call_1', 'calc', '{ "operator": "+", "args": [2, 2] }')
    )
  ]

  @Shared
  protected static final TOOL_OUTPUT = TOOL_CALL + [AIGuard.Message.tool('call_1', '5')]

  @Shared
  protected static final PROMPT = TOOL_OUTPUT + [AIGuard.Message.message('assistant', '2 + 2 is 5'), AIGuard.Message.message('user', '')]

  protected ClientAndServer server
  protected HttpUrl serverUrl
  protected AgentSpan span
  protected AgentSpan localRootSpan

  void setup() {
    server = startClientAndServer()
    serverUrl = HttpUrl.parse("http://localhost:${server.port}/api/v2/ai-guard/evaluate")

    injectEnvConfig('SERVICE', 'ai_guard_test')
    injectEnvConfig('ENV', 'test')

    span = Mock(AgentSpan)
    localRootSpan = Mock(AgentSpan)
    span.getLocalRootSpan() >> localRootSpan
    final builder = Mock(AgentTracer.SpanBuilder) {
      start() >> span
    }
    final tracer = Stub(AgentTracer.TracerAPI) {
      buildSpan(_ as String, _ as String) >> builder
    }
    AgentTracer.forceRegister(tracer)

    WafMetricCollector.get().tap {
      prepareMetrics()
      drain()
    }
  }

  void cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
    AIGuardInternal.uninstall()
    server?.stop()
  }

  void 'test missing api/app keys'() {
    given:
    if (apiKey) {
      injectEnvConfig('API_KEY', apiKey)
    }
    if (appKey) {
      injectEnvConfig('APP_KEY', appKey)
    }

    when:
    AIGuardInternal.install()

    then:
    thrown(AIGuardInternal.BadConfigurationException)

    where:
    apiKey   | appKey
    'apiKey' | null
    'apiKey' | ''
    null     | 'appKey'
    ''       | 'appKey'
    null     | null
    ''       | ''
  }

  void 'test endpoint discovery'() {
    given:
    injectEnvConfig('API_KEY', 'api')
    injectEnvConfig('APP_KEY', 'app')
    if (endpoint != null) {
      injectEnvConfig("AI_GUARD_ENDPOINT", endpoint)
    } else {
      removeEnvConfig("AI_GUARD_ENDPOINT")
    }
    if (site != null) {
      injectEnvConfig('SITE', site)
    } else {
      removeEnvConfig('SITE')
    }

    when:
    AIGuardInternal.install()

    then:
    final internal = (AIGuardInternal) AIGuard.EVALUATOR
    internal.url.toString() == expected

    where:
    endpoint       | site            | expected
    'https://test' | null            | 'https://test/evaluate'
    null           | null            | 'https://app.datadoghq.com/api/v2/ai-guard/evaluate'
    null           | 'datadoghq.com' | 'https://app.datadoghq.com/api/v2/ai-guard/evaluate'
    null           | 'datad0g.com'   | 'https://app.datad0g.com/api/v2/ai-guard/evaluate'
  }

  void 'test evaluate'() {
    given:
    Throwable error = null
    AIGuard.Evaluation eval = null
    Map<String, Object> receivedMeta = null
    final throwAbortError = suite.blocking && suite.action != ALLOW

    // Setup mock response
    server.when(HttpRequest.request()
      .withMethod('POST')
      .withPath('/api/v2/ai-guard/evaluate'))
      .respond(HttpResponse.response()
      .withStatusCode(200)
      .withContentType(MediaType.APPLICATION_JSON)
      .withBody(MOSHI.adapter(Object).toJson([
        data: [attributes: [action: suite.action, reason: suite.reason, tags: suite.tags ?: [], is_blocking_enabled: suite.blocking]]
      ])))

    final client = HttpClient.newBuilder().build()
    final aiguard = new AIGuardInternal(serverUrl, HEADERS, client)

    when:
    try {
      eval = aiguard.evaluate(suite.messages, new AIGuard.Options().block(suite.blocking))
    } catch (Throwable e) {
      error = e
    }

    then:
    1 * span.setTag(AIGuardInternal.TARGET_TAG, suite.target)
    1 * localRootSpan.setTag(DDTags.MANUAL_KEEP, true)
    if (suite.target == 'tool') {
      1 * span.setTag(AIGuardInternal.TOOL_TAG, 'calc')
    }
    1 * span.setTag(AIGuardInternal.ACTION_TAG, suite.action)
    1 * span.setTag(AIGuardInternal.REASON_TAG, suite.reason)
    1 * span.setMetaStruct(AIGuardInternal.META_STRUCT_TAG, _ as Map) >> {
      receivedMeta = it[1] as Map<String, Object>
      return span
    }
    if (throwAbortError) {
      1 * span.addThrowable(_ as AIGuard.AIGuardAbortError)
    }

    assertMeta(receivedMeta, suite)
    assertRequest(suite.messages)
    if (throwAbortError) {
      error instanceof AIGuard.AIGuardAbortError
      error.action == suite.action
      error.reason == suite.reason
      error.tags == suite.tags
    } else {
      error == null
      eval.action == suite.action
      eval.reason == suite.reason
      eval.tags == suite.tags
    }
    assertTelemetry('ai_guard.requests', "action:$suite.action", "block:$throwAbortError", 'error:false')

    where:
    suite << TestSuite.build()
  }

  void 'test evaluate with API errors'() {
    given:
    final errors = [[status: 400, title: 'Bad request']]
    final aiguard = mockServerClientJSon(404, [errors: errors])

    when:
    aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT)

    then:
    final exception = thrown(AIGuard.AIGuardClientError)
    exception.errors == errors
    1 * span.addThrowable(_ as AIGuard.AIGuardClientError)
    assertTelemetry('ai_guard.requests', 'error:true')
  }

  void 'test evaluate with invalid JSON'() {
    given:
    final aiguard = mockServerClientJSon(200, [bad: 'This is an invalid response'])

    when:
    aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT)

    then:
    thrown(AIGuard.AIGuardClientError)
    1 * span.addThrowable(_ as AIGuard.AIGuardClientError)
    assertTelemetry('ai_guard.requests', 'error:true')
  }

  void 'test evaluate with missing action'() {
    given:
    final aiguard = mockServerClientJSon(200, [data: [attributes: [reason: 'I miss something']]])

    when:
    aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT)

    then:
    thrown(AIGuard.AIGuardClientError)
    1 * span.addThrowable(_ as AIGuard.AIGuardClientError)
    assertTelemetry('ai_guard.requests', 'error:true')
  }

  void 'test evaluate with non JSON response'() {
    given:
    final aiguard = mockServerClient(200, 'I am no JSON')

    when:
    aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT)

    then:
    thrown(AIGuard.AIGuardClientError)
    1 * span.addThrowable(_ as AIGuard.AIGuardClientError)
    assertTelemetry('ai_guard.requests', 'error:true')
  }

  void 'test evaluate with empty response'() {
    given:
    final aiguard = mockServerClient(200, '')

    when:
    aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT)

    then:
    thrown(AIGuard.AIGuardClientError)
    1 * span.addThrowable(_ as AIGuard.AIGuardClientError)
    assertTelemetry('ai_guard.requests', 'error:true')
  }

  void 'test message length truncation'() {
    given:
    final maxMessages = Config.get().getAiGuardMaxMessagesLength()
    final aiguard = mockServerClientJSon(200, [data: [attributes: [action: 'ALLOW', reason: 'It is fine']]])
    final messages = (0..maxMessages)
      .collect { AIGuard.Message.message('user', "This is a prompt: ${it}") }
      .toList()

    when:
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT)

    then:
    1 * span.setMetaStruct(AIGuardInternal.META_STRUCT_TAG, _) >> {
      final received = (List<AIGuard.Message>) it[1].messages
      assert received.size() == maxMessages
      assert received.size() < messages.size()
    }
    assertTelemetry('ai_guard.truncated', 'type:messages')
  }

  void 'test message content truncation'() {
    given:
    final maxContent = Config.get().getAiGuardMaxContentSize()
    final aiguard = mockServerClientJSon(200, [data: [attributes: [action: 'ALLOW', reason: 'It is fine']]])
    final message = AIGuard.Message.message("user", (0..maxContent).collect { 'A' }.join())

    when:
    aiguard.evaluate([message], AIGuard.Options.DEFAULT)

    then:
    1 * span.setMetaStruct(AIGuardInternal.META_STRUCT_TAG, _) >> {
      final received = (List<AIGuard.Message>) it[1].messages
      received.last().with {
        assert it.content.length() == maxContent
        assert it.content.length() < message.content.length()
      }
    }
    assertTelemetry('ai_guard.truncated', 'type:content')
  }

  void 'test no messages'() {
    given:
    final aiguard = new AIGuardInternal(serverUrl, HEADERS, Stub(HttpClient))

    when:
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT)

    then:
    thrown(IllegalArgumentException)


    where:
    messages << [[], null]
  }

  void 'test missing tool name'() {
    given:
    final aiguard = mockServerClientJSon(200, [data: [attributes: [action: 'ALLOW', reason: 'Just do it']]])

    when:
    aiguard.evaluate([AIGuard.Message.tool('call_1', 'Content')], AIGuard.Options.DEFAULT)

    then:
    1 * span.setTag(AIGuardInternal.TARGET_TAG, 'tool')
    0 * span.setTag(AIGuardInternal.TOOL_TAG, _)
  }

  void 'map requires even number of params'() {
    when:
    AIGuardInternal.mapOf('1', '2', '3')

    then:
    thrown(IllegalArgumentException)
  }

  void 'test message immutability'() {
    given:
    final aiguard = mockServerClientJSon(200, [data: [attributes: [action: 'ALLOW', reason: 'Just do it']]])
    final messages = [
      new AIGuard.Message(
      "assistant",
      (String) null,
      [AIGuard.ToolCall.toolCall('call_1', 'execute_shell', '{"cmd": "ls -lah"}')],
      null
      )
    ]
    Map<String, Object> receivedMeta

    when:
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT)

    then:
    1 * span.finish() >> {
      // modify the messages before serialization
      messages.first().toolCalls.add(
        AIGuard.ToolCall.toolCall('call_2', 'execute_shell', '{"cmd": "rm -rf"}')
        )
      messages.add(AIGuard.Message.tool('call_1', 'dir1, dir2, dir3'))
    }
    1 * span.setMetaStruct(AIGuardInternal.META_STRUCT_TAG, _ as Map) >> {
      receivedMeta = it[1] as Map<String, Object>
      return span
    }
    final metaStructMessages = receivedMeta.messages as List<AIGuard.Message>
    metaStructMessages.size() != messages.size()
    metaStructMessages.size() == 1
    metaStructMessages.first().toolCalls.size() != messages.first().toolCalls.size()
    metaStructMessages.first().toolCalls.size() == 1
  }

  private AIGuardInternal mockServerClientJSon(final int status, final Object json) {
    mockServerClient(status, MOSHI.adapter(Object).toJson(json))
  }

  private AIGuardInternal mockServerClient(final int status, final String responseBody) {
    server.when(HttpRequest.request()
      .withMethod('POST')
      .withPath('/api/v2/ai-guard/evaluate'))
      .respond(HttpResponse.response()
      .withStatusCode(status)
      .withContentType(MediaType.APPLICATION_JSON)
      .withBody(responseBody))
    final client = HttpClient.newBuilder().build()
    return new AIGuardInternal(serverUrl, HEADERS, client)
  }

  private static assertTelemetry(final String metric, final String...tags) {
    final metrics = WafMetricCollector.get().with {
      prepareMetrics()
      drain()
    }
    final filtered = metrics.findAll {
      it.namespace == 'appsec'
      && it.metricName == metric
      && it.tags == tags.toList()
    }
    assert filtered.size() == 1 : metrics
    assert filtered*.value.sum() == 1
    return true
  }

  private static assertMeta(final Map<String, Object> meta, final TestSuite suite) {
    if (suite.tags) {
      assert meta.attack_categories == suite.tags
    }
    final receivedMessages = snakeCaseJson(meta.messages)
    final expectedMessages = snakeCaseJson(suite.messages)
    JSONAssert.assertEquals(expectedMessages, receivedMessages, JSONCompareMode.NON_EXTENSIBLE)
    return true
  }

  private void assertRequest(final List<AIGuard.Message> messages) {
    final expectedBody = snakeCaseJson([data: [attributes: [messages: messages, meta: [service: 'ai_guard_test', env: 'test']]]])
    def expectedRequest = HttpRequest.request()
    .withMethod('POST')
    .withPath('/api/v2/ai-guard/evaluate')
    .withBody(JsonBody.json(expectedBody))
    HEADERS.each { entry ->
      expectedRequest = expectedRequest.withHeader(entry.key, entry.value)
    }
    server.verify(expectedRequest, VerificationTimes.once())
  }

  private static String snakeCaseJson(final Object value) {
    MAPPER.writeValueAsString(value)
  }

  void 'test JSON serialization with text content parts'() {
    given:
    final aiguard = mockClient(200, [data: [attributes: [action: 'ALLOW', reason: 'Good']]])
    final messages = [AIGuard.Message.message('user', [AIGuard.ContentPart.text('Hello world')])]

    when:
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT)

    then:
    1 * span.setMetaStruct(AIGuardInternal.META_STRUCT_TAG, _) >> {
      final meta = it[1] as Map<String, Object>
      final receivedMessages = meta.messages as List<AIGuard.Message>
      assert receivedMessages.size() == 1
      assert receivedMessages[0].contentParts.size() == 1
      assert receivedMessages[0].contentParts[0].type == AIGuard.ContentPart.Type.TEXT
      assert receivedMessages[0].contentParts[0].text == 'Hello world'
      return span
    }
  }

  void 'test JSON serialization with image_url content parts'() {
    given:
    final aiguard = mockClient(200, [data: [attributes: [action: 'ALLOW', reason: 'Good']]])
    final messages = [
      AIGuard.Message.message('user', [AIGuard.ContentPart.imageUrl('https://example.com/image.jpg')])
    ]

    when:
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT)

    then:
    1 * span.setMetaStruct(AIGuardInternal.META_STRUCT_TAG, _) >> {
      final meta = it[1] as Map<String, Object>
      final receivedMessages = meta.messages as List<AIGuard.Message>
      assert receivedMessages.size() == 1
      assert receivedMessages[0].contentParts.size() == 1
      assert receivedMessages[0].contentParts[0].type == AIGuard.ContentPart.Type.IMAGE_URL
      assert receivedMessages[0].contentParts[0].imageUrl.url == 'https://example.com/image.jpg'
      return span
    }
  }

  void 'test JSON serialization with mixed content parts'() {
    given:
    final aiguard = mockClient(200, [data: [attributes: [action: 'ALLOW', reason: 'Good']]])
    final messages = [
      AIGuard.Message.message('user', [
        AIGuard.ContentPart.text('Describe this image:'),
        AIGuard.ContentPart.imageUrl('https://example.com/image.jpg'),
        AIGuard.ContentPart.text('What do you see?')
      ])
    ]

    when:
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT)

    then:
    1 * span.setMetaStruct(AIGuardInternal.META_STRUCT_TAG, _) >> {
      final meta = it[1] as Map<String, Object>
      final receivedMessages = meta.messages as List<AIGuard.Message>
      assert receivedMessages.size() == 1
      assert receivedMessages[0].contentParts.size() == 3
      assert receivedMessages[0].contentParts[0].type == AIGuard.ContentPart.Type.TEXT
      assert receivedMessages[0].contentParts[0].text == 'Describe this image:'
      assert receivedMessages[0].contentParts[1].type == AIGuard.ContentPart.Type.IMAGE_URL
      assert receivedMessages[0].contentParts[1].imageUrl.url == 'https://example.com/image.jpg'
      assert receivedMessages[0].contentParts[2].type == AIGuard.ContentPart.Type.TEXT
      assert receivedMessages[0].contentParts[2].text == 'What do you see?'
      return span
    }
  }

  void 'test content parts order is preserved'() {
    given:
    final aiguard = mockClient(200, [data: [attributes: [action: 'ALLOW', reason: 'Good']]])
    final parts = (0..4).collect {
      it % 2 == 0 ? AIGuard.ContentPart.text("Text $it") : AIGuard.ContentPart.imageUrl("https://example.com/image${it}.jpg")
    }
    final messages = [AIGuard.Message.message('user', parts)]

    when:
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT)

    then:
    1 * span.setMetaStruct(AIGuardInternal.META_STRUCT_TAG, _) >> {
      final meta = it[1] as Map<String, Object>
      final receivedMessages = meta.messages as List<AIGuard.Message>
      assert receivedMessages[0].contentParts.size() == 5
      (0..4).each { i ->
        if (i % 2 == 0) {
          assert receivedMessages[0].contentParts[i].type == AIGuard.ContentPart.Type.TEXT
          assert receivedMessages[0].contentParts[i].text == "Text $i"
        } else {
          assert receivedMessages[0].contentParts[i].type == AIGuard.ContentPart.Type.IMAGE_URL
          assert receivedMessages[0].contentParts[i].imageUrl.url == "https://example.com/image${i}.jpg"
        }
      }
      return span
    }
  }

  void 'test content part text truncation'() {
    given:
    final maxContent = Config.get().getAiGuardMaxContentSize()
    final aiguard = mockClient(200, [data: [attributes: [action: 'ALLOW', reason: 'Good']]])
    final longText = (0..maxContent).collect { 'A' }.join()
    final messages = [
      AIGuard.Message.message('user', [AIGuard.ContentPart.text(longText), AIGuard.ContentPart.text('Short text')])
    ]

    when:
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT)

    then:
    1 * span.setMetaStruct(AIGuardInternal.META_STRUCT_TAG, _) >> {
      final meta = it[1] as Map<String, Object>
      final receivedMessages = meta.messages as List<AIGuard.Message>
      assert receivedMessages[0].contentParts.size() == 2
      assert receivedMessages[0].contentParts[0].text.length() == maxContent
      assert receivedMessages[0].contentParts[0].text.length() < longText.length()
      assert receivedMessages[0].contentParts[1].text == 'Short text'
      return span
    }
    assertTelemetry('ai_guard.truncated', 'type:content')
  }

  void 'test content part image_url not truncated even with long data URI'() {
    given:
    final maxContent = Config.get().getAiGuardMaxContentSize()
    final aiguard = mockClient(200, [data: [attributes: [action: 'ALLOW', reason: 'Good']]])
    // Create a very long data URI (longer than max content size)
    final longDataUri = 'data:image/png;base64,' + (0..(maxContent + 1000)).collect { 'A' }.join()
    final messages = [
      AIGuard.Message.message('user', [
        AIGuard.ContentPart.text('Image:'),
        AIGuard.ContentPart.imageUrl(longDataUri)
      ])
    ]

    when:
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT)

    then:
    1 * span.setMetaStruct(AIGuardInternal.META_STRUCT_TAG, _) >> {
      final meta = it[1] as Map<String, Object>
      final receivedMessages = meta.messages as List<AIGuard.Message>
      assert receivedMessages[0].contentParts.size() == 2
      assert receivedMessages[0].contentParts[1].type == AIGuard.ContentPart.Type.IMAGE_URL
      // Image URL should NOT be truncated
      assert receivedMessages[0].contentParts[1].imageUrl.url == longDataUri
      assert receivedMessages[0].contentParts[1].imageUrl.url.length() > maxContent
      return span
    }
  }

  void 'test backward compatibility with string content'() {
    given:
    final aiguard = mockClient(200, [data: [attributes: [action: 'ALLOW', reason: 'Good']]])
    final messages = [AIGuard.Message.message('user', 'Hello world')]

    when:
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT)

    then:
    1 * span.setMetaStruct(AIGuardInternal.META_STRUCT_TAG, _) >> {
      final meta = it[1] as Map<String, Object>
      final receivedMessages = meta.messages as List<AIGuard.Message>
      assert receivedMessages.size() == 1
      assert receivedMessages[0].content == 'Hello world'
      assert receivedMessages[0].contentParts == null
      return span
    }
  }

  private static class TestSuite {
    private final AIGuard.Action action
    private final String reason
    private final List<String> tags
    private final boolean blocking
    private final String description
    private final String target
    private final List<AIGuard.Message> messages

    TestSuite(AIGuard.Action action, String reason, List<String> tags, boolean blocking, String description, String target, List<AIGuard.Message> messages) {
      this.action = action
      this.reason = reason
      this.tags = tags
      this.blocking = blocking
      this.description = description
      this.target = target
      this.messages = messages
    }

    static List<TestSuite> build() {
      def actionValues = [
        [ALLOW, 'Go ahead', []],
        [DENY, 'Nope', ['deny_everything', 'test_deny']],
        [ABORT, 'Kill it with fire', ['alarm_tag', 'abort_everything']]
      ]
      def blockingValues = [true, false]
      def suiteValues = [
        ['tool call', 'tool', TOOL_CALL],
        ['tool output', 'tool', TOOL_OUTPUT],
        ['prompt', 'prompt', PROMPT]
      ]
      return combinations([actionValues, blockingValues, suiteValues] as Iterable)
      .collect { action, blocking, suite ->
        new TestSuite(action[0], action[1], action[2], blocking, suite[0], suite[1], suite[2])
      }
    }


    @Override
    String toString() {
      return "TestSuite{" +
      "description='" + description + '\'' +
      ", action=" + action +
      ", reason='" + reason + '\'' +
      ", blocking=" + blocking +
      ", target='" + target + '\'' +
      ", messages=" + messages + '\'' +
      ", tags=" + tags +
      '}'
    }
  }
}
