package datadog.smoketest

import datadog.trace.test.agent.decoder.DecodedSpan
import groovy.json.JsonSlurper
import okhttp3.Request
import spock.util.concurrent.PollingConditions

import java.util.function.Function
import java.util.function.Predicate

import static datadog.trace.api.config.IastConfig.*

class Jersey3SmokeTest extends AbstractServerSmokeTest {

  private static final String TAG_NAME = '_dd.iast.json'

  @Override
  def logLevel(){
    return "debug"
  }

  @Override
  Closure decodedTracesCallback() {
    return {} // force traces decoding
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String jarPath = System.getProperty("datadog.smoketest.jersey3.jar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll([
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_REQUEST_SAMPLING, 100),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
      withSystemProperty(IAST_DEDUPLICATION_ENABLED, false),
      withSystemProperty(IAST_REDACTION_ENABLED, false),
      withSystemProperty("integration.grizzly.enabled", true)
    ])
    //command.add("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000")
    //command.add("-Xdebug")
    command.addAll((String[]) ["-jar", jarPath, httpPort])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  def "Test path parameter in Jersey"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/bypathparam/pathParamValue"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello pathParamValue")
    assert response.code() == 200
    waitForSpan(new PollingConditions(timeout: 5),
    hasVulnerability(type('SQL_INJECTION').and(evidence('pathParamValue'))))
  }

  def "Test query parameter in Jersey"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/byqueryparam?param=queryParamValue"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello queryParamValue")
    assert response.code() == 200
    waitForSpan(new PollingConditions(timeout: 5),
    hasVulnerability(type('SQL_INJECTION').and(evidence('queryParamValue'))))
  }

  def "Test header in Jersey"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/byheader"

    when:
    def request = new Request.Builder().url(url).header("X-Custom-header", "pepito").get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello pepito")
    assert response.code() == 200
    waitForSpan(new PollingConditions(timeout: 5),
    hasVulnerability(type('SQL_INJECTION').and(evidence('pepito'))))
  }

  def "Test cookie in Jersey"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/bycookie"

    when:
    def request = new Request.Builder().url(url).addHeader("Cookie", "cookieName=cookieValue").get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello cookieValue")
    assert response.code() == 200
    waitForSpan(new PollingConditions(timeout: 5),
    hasVulnerability(type('SQL_INJECTION').and(evidence('cookieValue'))))
  }

  private static String withSystemProperty(final String config, final Object value) {
    return "-Ddd.${config}=${value}"
  }

  private static Function<DecodedSpan, Boolean> hasVulnerability(final Predicate<?> predicate) {
    return { span ->
      final iastMeta = span.meta.get(TAG_NAME)
      if (!iastMeta) {
        return false
      }
      final vulnerabilities = parseVulnerabilities(iastMeta)
      return vulnerabilities.stream().anyMatch(predicate)
    }
  }

  private static Predicate<?> type(final String type) {
    return new Predicate<Object>() {
        @Override
        boolean test(Object vul) {
          return vul['type'] == type
        }
      }
  }

  private static Collection<?> parseVulnerabilities(final String log, final int startIndex) {
    final chars = log.toCharArray()
    final builder = new StringBuilder()
    def level = 0
    for (int i = log.indexOf('{', startIndex); i < chars.length; i++) {
      final current = chars[i]
      if (current == '{' as char) {
        level++
      } else if (current == '}' as char) {
        level--
      }
      builder.append(chars[i])
      if (level == 0) {
        break
      }
    }
    return parseVulnerabilities(builder.toString())
  }

  private static Collection<?> parseVulnerabilities(final String iastJson) {
    final slurper = new JsonSlurper()
    final parsed = slurper.parseText(iastJson)
    return parsed['vulnerabilities'] as Collection
  }

  private static Predicate<?> evidence(final String value) {
    return new Predicate<Object>() {
        @Override
        boolean test(Object vul) {
          return vul['evidence']['valueParts'][1]['value'] == value
        }
      }
  }
}
