package datadog.smoketest


import groovy.transform.CompileDynamic
import okhttp3.MediaType
import okhttp3.Request
import spock.util.concurrent.PollingConditions

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_REDACTION_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_REQUEST_SAMPLING

@CompileDynamic
class IastSpringBootRedirectSmokeTest extends AbstractSpringBootIastTest {

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

  def "unvalidated  redirect from addheader is present"() {
    setup:
    String url = "http://localhost:${httpPort}/unvalidated_redirect_from_header?param=redirected"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isRedirect()
    response.header("Location").contains("redirected")
    waitForSpan(new PollingConditions(timeout: 5),
    hasVulnerability(type('UNVALIDATED_REDIRECT')))
  }

  def "unvalidated  redirect from sendRedirect is present"() {
    setup:
    String url = "http://localhost:${httpPort}/unvalidated_redirect_from_send_redirect?param=redirected"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isRedirect()
    hasVulnerabilityInLogs(type('UNVALIDATED_REDIRECT').and(withSpan()))
  }
}
