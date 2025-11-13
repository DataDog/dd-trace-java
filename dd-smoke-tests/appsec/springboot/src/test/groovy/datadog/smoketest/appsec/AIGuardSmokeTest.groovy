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
    messages[0].with {
      assert role == 'system'
      assert content == 'You are a beautiful AI'
    }
    messages[1].with {
      assert role == 'user'
      assert content != null
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
}
