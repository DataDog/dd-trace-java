package com.datadog.aiguard

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.squareup.moshi.Moshi
import datadog.common.version.VersionInfo
import datadog.trace.api.Config
import datadog.trace.api.aiguard.AIGuard
import datadog.trace.api.telemetry.WafMetricCollector
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Okio
import spock.lang.Shared

import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode

import static datadog.trace.api.aiguard.AIGuard.Action.ABORT
import static datadog.trace.api.aiguard.AIGuard.Action.ALLOW
import static datadog.trace.api.aiguard.AIGuard.Action.DENY
import static org.codehaus.groovy.runtime.DefaultGroovyMethods.combinations

class AIGuardInternalTests extends DDSpecification {

  @Shared
  protected static final URL = HttpUrl.parse('https://app.datadoghq.com/api/v2/ai-guard/evaluate')

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

  protected AgentSpan span

  void setup() {
    injectEnvConfig('SERVICE', 'ai_guard_test')
    injectEnvConfig('ENV', 'test')

    span = Mock(AgentSpan)
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
    Request request = null
    Throwable error = null
    AIGuard.Evaluation eval = null
    Map<String, Object> receivedMeta = null
    final throwAbortError = suite.blocking && suite.action != ALLOW
    final call = Mock(Call) {
      execute() >> {
        return mockResponse(
          request,
          200,
          [data: [attributes: [action: suite.action, reason: suite.reason, tags: suite.tags ?: [], is_blocking_enabled: suite.blocking]]]
          )
      }
    }
    final client = Mock(OkHttpClient) {
      newCall(_ as Request) >> {
        request = (Request) it[0]
        return call
      }
    }
    final aiguard = new AIGuardInternal(URL, HEADERS, client)

    when:
    try {
      eval = aiguard.evaluate(suite.messages, new AIGuard.Options().block(suite.blocking))
    } catch (Throwable e) {
      error = e
    }

    then:
    1 * span.setTag(AIGuardInternal.TARGET_TAG, suite.target)
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

    receivedMeta.messages == suite.messages
    if (suite.tags) {
      receivedMeta.matching_rules == suite.tags
    }
    assertRequest(request, suite.messages)
    if (throwAbortError) {
      error instanceof AIGuard.AIGuardAbortError
      error.action == suite.action
      error.reason == suite.reason
    } else {
      error == null
      eval.action == suite.action
      eval.reason == suite.reason
    }
    assertTelemetry('ai_guard.requests', "action:$suite.action", "block:$throwAbortError", 'error:false')

    where:
    suite << TestSuite.build()
  }

  void 'test evaluate with API errors'() {
    given:
    final errors = [[status: 400, title: 'Bad request']]
    Request request = null
    final call = Mock(Call) {
      execute() >> {
        return mockResponse(request, 404, [errors: errors])
      }
    }
    final client = Mock(OkHttpClient) {
      newCall(_ as Request) >> {
        request = (Request) it[0]
        return call
      }
    }
    final aiguard = new AIGuardInternal(URL, HEADERS, client)

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
    Request request = null
    final call = Mock(Call) {
      execute() >> {
        return mockResponse(request, 200, [bad: 'This is an invalid response'])
      }
    }
    final client = Mock(OkHttpClient) {
      newCall(_ as Request) >> {
        request = (Request) it[0]
        return call
      }
    }
    final aiguard = new AIGuardInternal(URL, HEADERS, client)

    when:
    aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT)

    then:
    thrown(AIGuard.AIGuardClientError)
    1 * span.addThrowable(_ as AIGuard.AIGuardClientError)
    assertTelemetry('ai_guard.requests', 'error:true')
  }

  void 'test evaluate with missing action'() {
    given:
    Request request = null
    final call = Mock(Call) {
      execute() >> {
        return mockResponse(request, 200, [data: [attributes: [reason: 'I miss something']]])
      }
    }
    final client = Mock(OkHttpClient) {
      newCall(_ as Request) >> {
        request = (Request) it[0]
        return call
      }
    }
    final aiguard = new AIGuardInternal(URL, HEADERS, client)

    when:
    aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT)

    then:
    thrown(AIGuard.AIGuardClientError)
    1 * span.addThrowable(_ as AIGuard.AIGuardClientError)
    assertTelemetry('ai_guard.requests', 'error:true')
  }

  void 'test evaluate with non JSON response'() {
    given:
    Request request = null
    final call = Mock(Call) {
      execute() >> {
        return mockResponse(request, 200, [data: [attributes: [reason: 'I miss something']]])
      }
    }
    final client = Mock(OkHttpClient) {
      newCall(_ as Request) >> {
        request = (Request) it[0]
        return call
      }
    }
    final aiguard = new AIGuardInternal(URL, HEADERS, client)

    when:
    aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT)

    then:
    thrown(AIGuard.AIGuardClientError)
    1 * span.addThrowable(_ as AIGuard.AIGuardClientError)
    assertTelemetry('ai_guard.requests', 'error:true')
  }

  void 'test evaluate with empty response'() {
    given:
    Request request = null
    final call = Mock(Call) {
      execute() >> {
        return mockResponse(request, 200, null)
      }
    }
    final client = Mock(OkHttpClient) {
      newCall(_ as Request) >> {
        request = (Request) it[0]
        return call
      }
    }
    final aiguard = new AIGuardInternal(URL, HEADERS, client)

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
    Request request = null
    final call = Mock(Call) {
      execute() >> {
        return mockResponse(request, 200, [data: [attributes: [action: ALLOW, reason: 'It is fine']]])
      }
    }
    final client = Mock(OkHttpClient) {
      newCall(_ as Request) >> {
        request = (Request) it[0]
        return call
      }
    }
    final aiguard = new AIGuardInternal(URL, HEADERS, client)
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
    Request request = null
    final call = Mock(Call) {
      execute() >> {
        return mockResponse(request, 200, [data: [attributes: [action: ALLOW, reason: 'It is fine']]])
      }
    }
    final client = Mock(OkHttpClient) {
      newCall(_ as Request) >> {
        request = (Request) it[0]
        return call
      }
    }
    final aiguard = new AIGuardInternal(URL, HEADERS, client)
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
    final aiguard = new AIGuardInternal(URL, HEADERS, Stub(OkHttpClient))

    when:
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT)

    then:
    thrown(IllegalArgumentException)


    where:
    messages << [[], null]
  }

  void 'test missing tool name'() {
    given:
    def request
    final call = Mock(Call) {
      execute() >> {
        return mockResponse(
          request,
          200,
          [data: [attributes: [action: 'ALLOW', reason: 'Just do it']]]
          )
      }
    }
    final client = Mock(OkHttpClient) {
      newCall(_ as Request) >> {
        request = (Request) it[0]
        return call
      }
    }
    final aiguard = new AIGuardInternal(URL, HEADERS, client)

    when:
    aiguard.evaluate([AIGuard.Message.tool('call_1', 'Content')], AIGuard.Options.DEFAULT)

    then:
    1 * span.setTag(AIGuardInternal.TARGET_TAG, 'tool')
    0 * span.setTag(AIGuardInternal.TOOL_TAG, _)
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

  private static assertRequest(final Request request, final List<AIGuard.Message> messages) {
    assert request.url() == URL
    assert request.method() == 'POST'
    HEADERS.each { entry ->
      assert request.header(entry.key) == entry.value
    }
    assert request.body().contentType().toString().contains('application/json')
    final receivedBody = readRequestBody(request.body())
    final expectedBody = snakeCaseJson([data: [attributes: [messages: messages, meta: [service: 'ai_guard_test', env: 'test']]]])
    JSONAssert.assertEquals(expectedBody, receivedBody, JSONCompareMode.NON_EXTENSIBLE)
    return true
  }

  private static String snakeCaseJson(final Object value) {
    MAPPER.writeValueAsString(value)
  }

  private static String readRequestBody(final RequestBody body) {
    final output = new ByteArrayOutputStream()
    final buffer = Okio.buffer(Okio.sink(output))
    body.writeTo(buffer)
    buffer.flush()
    return new String(output.toByteArray())
  }

  private static Response mockResponse(final Request request, final int status, final Object body) {
    return new Response.Builder()
    .protocol(Protocol.HTTP_1_1)
    .message('ok')
    .request(request)
    .code(status)
    .body(body == null ? null : ResponseBody.create(MediaType.parse('application/json'), MOSHI.adapter(Object).toJson(body)))
    .build()
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
      ", messages=" + messages +
      '}'
    }
  }
}
