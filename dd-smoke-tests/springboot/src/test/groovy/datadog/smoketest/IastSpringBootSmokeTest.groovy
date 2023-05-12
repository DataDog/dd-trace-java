package datadog.smoketest

import datadog.trace.test.agent.decoder.DecodedSpan
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeoutException
import java.util.function.Function
import java.util.function.Predicate

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_REDACTION_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_REQUEST_SAMPLING

@CompileDynamic
class IastSpringBootSmokeTest extends AbstractServerSmokeTest {

  private static final String TAG_NAME = '_dd.iast.json'

  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8")

  @Override
  def logLevel() {
    return "debug"
  }

  @Override
  Closure decodedTracesCallback() {
    return {} // force traces decoding
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll([
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_REQUEST_SAMPLING, 100),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
      withSystemProperty(IAST_REDACTION_ENABLED, false)
    ])
    command.addAll((String[]) ["-jar", springBootShadowJar, "--server.port=${httpPort}"])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    // Spring will print all environment variables to the log, which may pollute it and affect log assertions.
    processBuilder.environment().clear()
    processBuilder
  }

  def "IAST subsystem starts"() {
    given: 'an initial request has succeeded'
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()
    client.newCall(request).execute()

    when: 'logs are read'
    String startMsg = null
    String errorMsg = null
    checkLogPostExit {
      if (it.contains("Not starting IAST subsystem")) {
        errorMsg = it
      }
      if (it.contains("IAST is starting")) {
        startMsg = it
      }
      // Check that there's no logged exception about missing classes from Datadog.
      // We had this problem before with JDK9StackWalker.
      if (it.contains("java.lang.ClassNotFoundException: datadog/")) {
        errorMsg = it
      }
    }

    then: 'there are no errors in the log and IAST has started'
    errorMsg == null
    startMsg != null
    !logHasErrors
  }

  def "default home page without errors"() {
    setup:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains("Sup Dawg")
    response.body().contentType().toString().contains("text/plain")
    response.code() == 200

    checkLogPostExit()
    !logHasErrors
  }

  def "iast.enabled tag is present"() {
    setup:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5), hasMetric('_dd.iast.enabled', 1))
  }

  def "weak hash vulnerability is present"() {
    setup:
    String url = "http://localhost:${httpPort}/weakhash"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5),
    hasVulnerability(type('WEAK_HASH').and(evidence('MD5'))))
  }

  def "insecure cookie vulnerability is present"() {
    setup:
    String url = "http://localhost:${httpPort}/insecure_cookie"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isSuccessful()
    response.header("Set-Cookie").contains("user-id")
    waitForSpan(new PollingConditions(timeout: 5),
    hasVulnerability(type('INSECURE_COOKIE').and(evidence('user-id'))))
  }

  def "insecure cookie  vulnerability from addheader is present"() {
    setup:
    String url = "http://localhost:${httpPort}/insecure_cookie_from_header"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isSuccessful()
    response.header("Set-Cookie").contains("user-id")
    waitForSpan(new PollingConditions(timeout: 5),
    hasVulnerability(type('INSECURE_COOKIE').and(evidence('user-id'))))
  }


  def "weak hash vulnerability is present on boot"() {
    setup:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()

    when: 'ensure the controller is loaded'
    client.newCall(request).execute()

    then: 'a vulnerability pops in the logs (startup traces might not always be available)'
    hasVulnerabilityInLogs(type('WEAK_HASH').and(evidence('SHA1')).and(withSpan()))
  }

  def "weak hash vulnerability is present on thread"() {
    setup:
    String url = "http://localhost:${httpPort}/async_weakhash"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5),
    hasVulnerability(type('WEAK_HASH').and(evidence('MD4')).and(withSpan())))
  }

  void "getParameter taints string"() {
    setup:
    String url = "http://localhost:${httpPort}/getparameter?param=A"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'A' &&
        tainted.ranges[0].source.name == 'param' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
  }

  void 'tainting of jwt'() {
    given:
    String url = "http://localhost:${httpPort}/jwt"
    String token = "Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJqYWNraWUiLCJpc3MiOiJtdm5zZWFyY2gifQ.C_q7_FwlzmvzC6L3CqOnUzb6PFs9REZ3RON6_aJTxWw"
    def request = new Request.Builder().url(url).header("Authorization", token).get().build()

    when:
    Response response = client.newCall(request).execute()

    then:
    response.successful
    response.body().string().contains("jackie")

    hasTainted {
      it.value == 'jackie' &&
        it.ranges[0].source.origin == 'http.request.header'
    }
  }

  def "command injection is present with runtime"() {
    setup:
    final url = "http://localhost:${httpPort}/cmdi/runtime?cmd=ls"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5), hasVulnerability(type("COMMAND_INJECTION")))
  }

  def "command injection is present with process builder"() {
    setup:
    final url = "http://localhost:${httpPort}/cmdi/process_builder?cmd=ls"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5), hasVulnerability(type("COMMAND_INJECTION")))
  }

  def "path traversal is present with file"() {
    setup:
    final url = "http://localhost:${httpPort}/path_traversal/file?path=test"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5), hasVulnerability(type("PATH_TRAVERSAL")))
  }

  def "path traversal is present with paths"() {
    setup:
    final url = "http://localhost:${httpPort}/path_traversal/paths?path=test"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5), hasVulnerability(type("PATH_TRAVERSAL")))
  }

  def "path traversal is present with path"() {
    setup:
    final url = "http://localhost:${httpPort}/path_traversal/path?path=test"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5), hasVulnerability(type("PATH_TRAVERSAL")))
  }

  def "parameter binding taints bean strings"() {
    setup:
    String url = "http://localhost:${httpPort}/param_binding/test?name=parameter&value=binding"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'binding' &&
        tainted.ranges[0].source.name == 'value' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
  }

  def "request header taint string"() {
    setup:
    String url = "http://localhost:${httpPort}/request_header/test"
    def request = new Request.Builder().url(url).header("test-header", "test").get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'test' &&
        tainted.ranges[0].source.name == 'test-header' &&
        tainted.ranges[0].source.origin == 'http.request.header'
    }
  }

  def "path param taint string"() {
    setup:
    String url = "http://localhost:${httpPort}/path_param?param=test"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'test' &&
        tainted.ranges[0].source.name == 'param' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
  }

  def "request body taint json"() {
    setup:
    String url = "http://localhost:${httpPort}/request_body/test"
    def request = new Request.Builder().url(url).post(RequestBody.create(JSON, '{"name": "nameTest", "value" : "valueTest"}')).build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'nameTest' &&
        tainted.ranges[0].source.origin == 'http.request.body'
    }
  }

  void 'request query string'() {
    given:
    final url = "http://localhost:${httpPort}/query_string?key=value"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'key=value' &&
        tainted.ranges[0].source.origin == 'http.request.query'
    }
  }

  void 'request cookie propagation'() {
    given:
    final url = "http://localhost:${httpPort}/cookie"
    final request = new Request.Builder().url(url).header('Cookie', 'name=value').get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'name' &&
        tainted.ranges[0].source.origin == 'http.request.cookie.name'
    }
    hasTainted { tainted ->
      tainted.value == 'value' &&
        tainted.ranges[0].source.name == 'name' &&
        tainted.ranges[0].source.origin == 'http.request.cookie.value'
    }
  }

  void 'tainting of path variables — simple variant'() {
    given:
    String url = "http://localhost:${httpPort}/simple/foobar"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted {
      it.value == 'foobar' &&
        it.ranges[0].source.origin == 'http.request.path.parameter' &&
        it.ranges[0].source.name == 'var1'
    }
  }

  void 'tainting of path variables — RequestMappingInfoHandlerMapping variant'() {
    given:
    String url = "http://localhost:${httpPort}/matrix/value;xxx=aaa,bbb;yyy=ccc/zzz=ddd"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      Map firstRange = tainted.ranges[0]
      tainted.value == 'value' &&
        firstRange?.source?.origin == 'http.request.path.parameter' &&
        firstRange?.source?.name == 'var1'
    }
    ['xxx', 'aaa', 'bbb', 'yyy', 'ccc'].each {
      hasTainted { tainted ->
        Map firstRange = tainted.ranges[0]
        firstRange?.source?.origin == 'http.request.matrix.parameter' &&
          firstRange?.source?.name == 'var1'
      }
    }
    hasTainted { tainted ->
      Map firstRange = tainted.ranges[0]
      tainted.value == 'zzz=ddd' &&
        firstRange?.source?.origin == 'http.request.path.parameter' &&
        firstRange?.source?.name == 'var2'
    }
    ['zzz', 'ddd'].each {
      hasTainted { tainted ->
        Map firstRange = tainted.ranges[0]
        tainted.value = it &&
          firstRange?.source?.origin == 'http.request.matrix.parameter' &&
          firstRange?.source?.name == 'var2'
      }
    }
  }

  void 'ssrf is present'() {
    setup:
    final url = "http://localhost:${httpPort}/ssrf"
    final body = new FormBody.Builder().add('url', 'https://dd.datad0g.com/').build()
    final request = new Request.Builder().url(url).post(body).build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5), hasVulnerability(type('SSRF')))
  }

  void 'test iast metrics stored in spans'() {
    setup:
    final url = "http://localhost:${httpPort}/cmdi/runtime?cmd=ls"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5),
    hasMetric('_dd.iast.telemetry.executed.sink.command_injection', 1))
  }

  private static Function<DecodedSpan, Boolean> hasMetric(final String name, final Object value) {
    return { span -> value == span.metrics.get(name) }
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

  private boolean hasVulnerabilityInLogs(final Predicate<?> predicate) {
    def found = false
    checkLogPostExit { final String log ->
      final index = log.indexOf(TAG_NAME)
      if (index >= 0) {
        final vulnerabilities = parseVulnerabilities(log, index)
        found |= vulnerabilities.stream().anyMatch(predicate)
      }
    }
    return found
  }

  private void hasTainted(final Closure<Boolean> matcher) {
    final slurper = new JsonSlurper()
    final tainteds = []
    try {
      processTestLogLines { String log ->
        final index = log.indexOf('tainted=')
        if (index >= 0) {
          final tainted = slurper.parse(new StringReader(log.substring(index + 8)))
          tainteds.add(tainted)
          if (matcher.call(tainted)) {
            return true // found
          }
        }
      }
    } catch (TimeoutException toe) {
      throw new AssertionError("No matching tainted found. Tainteds found: ${tainteds}")
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

  private static Predicate<?> type(final String type) {
    return { vul ->
      vul.type == type
    }
  }

  private static Predicate<?> evidence(final String value) {
    return { vul ->
      vul.evidence.value == value
    }
  }

  private static Predicate<?> withSpan() {
    return { vul ->
      vul.location.spanId > 0
    }
  }

  private static String withSystemProperty(final String config, final Object value) {
    return "-Ddd.${config}=${value}"
  }
}
