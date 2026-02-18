package datadog.smoketest.appsec

import datadog.trace.test.agent.decoder.DecodedSpan
import groovy.json.JsonSlurper
import okhttp3.Request
import spock.lang.Shared

class AIGuardSmokeTest extends AbstractAppSecServerSmokeTest {

  @Shared
  protected String[] defaultAIGuardProperties = [
    '-Ddd.ai_guard.enabled=true',
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
    command.addAll(defaultAppSecProperties)
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
    assert span.meta.get('ai_guard.action') == action
    assert span.meta.get('ai_guard.reason') == reason
    assert span.meta.get('ai_guard.target') == 'prompt'
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
