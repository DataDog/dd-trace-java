package datadog.smoketest.appsec

import datadog.trace.test.agent.decoder.DecodedSpan
import groovy.json.JsonSlurper
import okhttp3.Request
import spock.lang.Shared

class AIGuardSmokeTest extends AbstractAppSecServerSmokeTest {

  @Shared
  protected String[] defaultAIGuardProperties = [
    '-Ddd.ai_guard.enabled=true',
    // Make sure AI Guard features (e.g. client IP tags collection) do not depend on AppSec.
    '-Ddd.appsec.enabled=false',
    '-Ddd.trace.client-ip.enabled=false',
    '-Dsmoketest.skipAppSecActivation=true',
    "-Ddd.ai_guard.endpoint=http://localhost:${httpPort}/aiguard".toString(),
  ]

  @Override
  def logLevel() {
    'DEBUG'
  }

  @Override
  Closure decodedTracesCallback() {
    // just return the traces
    return {}
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    final springBootShadowJar = System.getProperty("datadog.smoketest.appsec.springboot.shadowJar.path")
    final command = [javaPath()]
    command.addAll(defaultJavaProperties)
    command.addAll(defaultAIGuardProperties)
    command.addAll(['-jar', springBootShadowJar, "--server.port=${httpPort}".toString()])
    final builder = new ProcessBuilder(command).directory(new File(buildDirectory))
    builder.environment().put('DD_APPLICATION_KEY', 'test')
    return builder
  }

  void 'test message evaluation'() {
    given:
    final blocking = test.blocking as boolean
    final action = test.action as String
    final reason = test.reason as String
    def request = new Request.Builder()
    .url("http://localhost:${httpPort}/aiguard${test.endpoint}")
    .header('X-Blocking-Enabled', "${blocking}")
    .get()
    .build()

    when:
    final response = client.newCall(request).execute()

    then:
    if (blocking && action != 'ALLOW') {
      assert response.code() == 403
      assert response.body().string().contains(reason)
    } else {
      assert response.code() == 200
      final body = new JsonSlurper().parse(response.body().bytes())
      assert body.reason == reason
      assert body.action == action
    }

    and:
    waitForTraceCount(2) // default call + internal API mock
    final span = traces*.spans
    ?.flatten()
    ?.find {
      it.resource == 'ai_guard'
    } as DecodedSpan
    final rootSpan = traces*.spans
    ?.flatten()
    ?.find {
      it.traceId == span.traceId && it.parentId == 0
    } as DecodedSpan
    assert span.meta.get('ai_guard.action') == action
    assert span.meta.get('ai_guard.reason') == reason
    assert span.meta.get('ai_guard.target') == 'prompt'
    assert rootSpan.metrics.get('_sampling_priority_v1') == 2
    final messages = span.metaStruct.get('ai_guard').messages as List<Map<String, Object>>
    assert messages.size() == 2
    with(messages[0]) {
      role == 'system'
      content == 'You are a beautiful AI'
    }
    with(messages[1]) {
      role == 'user'
      content != null
    }

    where:
    test << testSuite()
  }

  private static List<?> testSuite() {
    return combinations([
      [endpoint: '/allow', action: 'ALLOW', reason: 'The prompt looks harmless'],
      [endpoint: '/deny', action: 'DENY', reason: 'I am feeling suspicious today'],
      [endpoint: '/abort', action: 'ABORT', reason: 'The user is trying to destroy me']
    ], [[blocking: true], [blocking: false],])
  }

  private static List<?> combinations(list1, list2) {
    list1.collectMany { a ->
      list2.collect { b ->
        a + b
      }
    }
  }

  void 'test default options honors remote blocking'() {
    given:
    def request = new Request.Builder()
    .url("http://localhost:${httpPort}/aiguard/deny-default-options")
    .get()
    .build()

    when:
    final response = client.newCall(request).execute()

    then:
    assert response.code() == 403
    assert response.body().string().contains('I am feeling suspicious today')

    and:
    waitForTraceCount(2)
    final span = traces*.spans
    ?.flatten()
    ?.find {
      it.resource == 'ai_guard'
    } as DecodedSpan
    assert span.meta.get('ai_guard.action') == 'DENY'
    assert span.meta.get('ai_guard.blocked') == 'true'
  }

  void 'client ip tags are added to the local root span when an ai_guard span is created'() {
    given:
    final publicIp = '5.6.7.9'
    final request = new Request.Builder()
    .url("http://localhost:${httpPort}/aiguard/allow")
    .header('X-Forwarded-For', publicIp)
    .get()
    .build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.code() == 200

    and:
    waitForTraceCount(2) // /aiguard/allow + internal /aiguard/evaluate mock
    final aiGuardSpan = traces*.spans
    ?.flatten()
    ?.find { it.resource == 'ai_guard' } as DecodedSpan
    aiGuardSpan != null
    final rootSpan = traces*.spans
    ?.flatten()
    ?.find { it.traceId == aiGuardSpan.traceId && it.parentId == 0 } as DecodedSpan
    rootSpan != null
    rootSpan.meta.get('http.client_ip') == publicIp
    rootSpan.meta.get('network.client.ip') != null
    rootSpan.meta.get('network.client.ip') != publicIp
  }

  void 'anomaly detection tags are copied from the local root span to the ai_guard span'() {
    given:
    final publicIp = '5.6.7.9'
    final userId = 'u12345'
    final sessionId = 's12345'
    final userAgent = 'AIGuardSmokeTest/1.0'
    final request = new Request.Builder()
    .url("http://localhost:${httpPort}/aiguard/allow")
    .header('X-Forwarded-For', publicIp)
    .header('X-User-Id', userId)
    .header('X-Session-Id', sessionId)
    .header('User-Agent', userAgent)
    .get()
    .build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.code() == 200

    and:
    waitForTraceCount(2) // /aiguard/allow + internal /aiguard/evaluate mock
    final aiGuardSpan = traces*.spans
    ?.flatten()
    ?.find { it.resource == 'ai_guard' } as DecodedSpan
    aiGuardSpan != null
    final rootSpan = traces*.spans
    ?.flatten()
    ?.find { it.traceId == aiGuardSpan.traceId && it.parentId == 0 } as DecodedSpan
    rootSpan != null

    // Tags must match what is on the root span
    aiGuardSpan.meta.get('ai_guard.http.client_ip') == rootSpan.meta.get('http.client_ip')
    aiGuardSpan.meta.get('ai_guard.network.client.ip') == rootSpan.meta.get('network.client.ip')
    aiGuardSpan.meta.get('ai_guard.http.useragent') == rootSpan.meta.get('http.useragent')
    aiGuardSpan.meta.get('ai_guard.usr.id') == rootSpan.meta.get('usr.id')
    aiGuardSpan.meta.get('ai_guard.usr.session_id') == rootSpan.meta.get('usr.session_id')

    // And carry the expected values
    aiGuardSpan.meta.get('ai_guard.http.client_ip') == publicIp
    aiGuardSpan.meta.get('ai_guard.http.useragent') == userAgent
    aiGuardSpan.meta.get('ai_guard.usr.id') == userId
    aiGuardSpan.meta.get('ai_guard.usr.session_id') == sessionId
  }

  void 'test multimodal content parts evaluation'() {
    given:
    def request = new Request.Builder()
    .url("http://localhost:${httpPort}/aiguard/multimodal")
    .get()
    .build()

    when:
    final response = client.newCall(request).execute()

    then:
    assert response.code() == 200
    final body = new JsonSlurper().parse(response.body().bytes())
    assert body.reason == 'Multimodal prompt detected'
    assert body.action == 'ALLOW'

    and:
    waitForTraceCount(2) // default call + internal API mock
    final span = traces*.spans
    ?.flatten()
    ?.find {
      it.resource == 'ai_guard'
    } as DecodedSpan
    assert span.meta.get('ai_guard.action') == 'ALLOW'
    assert span.meta.get('ai_guard.reason') == 'Multimodal prompt detected'
    assert span.meta.get('ai_guard.target') == 'prompt'

    // Verify content parts in metaStruct
    final messages = span.metaStruct.get('ai_guard').messages as List<Map<String, Object>>
    assert messages.size() == 2
    with(messages[0]) {
      role == 'system'
      content == 'You are a beautiful AI'
    }
    with(messages[1]) {
      role == 'user'
      def contentParts = it.content as List<Map<String, Object>>
      assert contentParts != null
      assert contentParts.size() == 3

      with(contentParts[0]) {
        type == 'text'
        text == 'Describe this image:'
      }

      with(contentParts[1]) {
        type == 'image_url'
        def imageUrl = it.image_url as Map<String, Object>
        assert imageUrl.url == 'https://example.com/image.jpg'
      }

      with(contentParts[2]) {
        type == 'text'
        text == 'What do you see?'
      }
    }
  }
}
