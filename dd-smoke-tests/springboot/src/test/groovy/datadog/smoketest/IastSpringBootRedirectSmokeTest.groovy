package datadog.smoketest

import groovy.transform.CompileDynamic
import okhttp3.Request

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_REDACTION_ENABLED

@CompileDynamic
class IastSpringBootRedirectSmokeTest extends AbstractIastServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll([
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
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
    hasVulnerability { vul -> vul.type == 'UNVALIDATED_REDIRECT' && vul.location.method == 'unvalidatedRedirectFromHeader' }
  }

  def "unvalidated  redirect from sendRedirect is present"() {
    setup:
    String url = "http://localhost:${httpPort}/unvalidated_redirect_from_send_redirect?param=redirected"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isRedirect()
    // TODO: span deserialization fails when checking the vulnerability
    // === Failure during message v0.4 decoding ===
    //org.msgpack.core.MessageTypeException: Expected String, but got Nil (c0)
    hasVulnerabilityInLogs { vul -> vul.type == 'UNVALIDATED_REDIRECT' && vul.location.method == 'unvalidatedRedirectFromSendRedirect' }
  }

  def "unvalidated  redirect from forward is present"() {
    setup:
    String url = "http://localhost:${httpPort}/unvalidated_redirect_from_forward?param=redirected"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerabilityInLogs { vul -> vul.type == 'UNVALIDATED_REDIRECT' && vul.location.method == 'unvalidatedRedirectFromForward' }
  }

  def "unvalidated  redirect from RedirectView is present"() {
    setup:
    String url = "http://localhost:${httpPort}/unvalidated_redirect_from_redirect_view?param=redirected"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isRedirect()
    hasVulnerabilityInLogs { vul -> vul.type == 'UNVALIDATED_REDIRECT' && vul.location.method == 'unvalidatedRedirectFromRedirectView' }
  }

  def "unvalidated  redirect from ModelAndView is present"() {
    setup:
    String url = "http://localhost:${httpPort}/unvalidated_redirect_from_model_and_view?param=redirected"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isRedirect()
    hasVulnerabilityInLogs { vul -> vul.type == 'UNVALIDATED_REDIRECT' && vul.location.method == 'unvalidatedRedirectFromModelAndView' }
  }


  def "unvalidated  redirect forward from ModelAndView is present"() {
    setup:
    String url = "http://localhost:${httpPort}/unvalidated_redirect_forward_from_model_and_view?param=redirected"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerabilityInLogs { vul -> vul.type == 'UNVALIDATED_REDIRECT' && vul.location.method == 'unvalidatedRedirectForwardFromModelAndView' }
  }


  def "unvalidated  redirect from string"() {
    setup:
    String url = "http://localhost:${httpPort}/unvalidated_redirect_from_string?param=redirected"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isRedirect()
    hasVulnerabilityInLogs { vul -> vul.type == 'UNVALIDATED_REDIRECT' && vul.location.method == 'unvalidatedRedirectFromString' }
  }

  def "unvalidated  redirect forward from string"() {
    setup:
    String url = "http://localhost:${httpPort}/unvalidated_redirect_forward_from_string?param=redirected"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    hasVulnerabilityInLogs { vul -> vul.type == 'UNVALIDATED_REDIRECT' && vul.location.method == 'unvalidatedRedirectForwardFromString' }
  }

  def "get View from tainted string"() {
    setup:
    String url = "http://localhost:${httpPort}/get_view_from_tainted_string?param=redirected"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    hasVulnerabilityInLogs { vul -> vul.type == 'UNVALIDATED_REDIRECT' && vul.location.method == 'getViewfromTaintedString' }
  }

}
