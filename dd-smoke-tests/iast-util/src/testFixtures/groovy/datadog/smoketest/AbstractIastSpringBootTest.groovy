package datadog.smoketest

import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED

abstract class AbstractIastSpringBootTest extends AbstractIastServerSmokeTest {

  private static final MediaType JSON = MediaType.parse('application/json; charset=utf-8')

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty('datadog.smoketest.springboot.shadowJar.path')

    List<String> command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(iastJvmOpts())
    command.addAll((String[]) ['-jar', springBootShadowJar, "--server.port=${httpPort}"])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    // Spring will print all environment variables to the log, which may pollute it and affect log assertions.
    processBuilder.environment().clear()
    return processBuilder
  }

  protected List<String> iastJvmOpts() {
    return [
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
    ]
  }

  void 'IAST subsystem starts'() {
    given: 'an initial request has succeeded'
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()
    client.newCall(request).execute()

    when: 'logs are read'
    String startMsg = null
    String errorMsg = null
    checkLogPostExit {
      if (it.contains('Not starting IAST subsystem')) {
        errorMsg = it
      }
      if (it.contains('IAST is starting')) {
        startMsg = it
      }
      // Check that there's no logged exception about missing classes from Datadog.
      // We had this problem before with JDK9StackWalker.
      if (it.contains('java.lang.ClassNotFoundException: datadog/')) {
        errorMsg = it
      }
    }

    then: 'there are no errors in the log and IAST has started'
    errorMsg == null
    startMsg != null
    !logHasErrors
  }

  void 'default home page without errors'() {
    setup:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains('Sup Dawg')
    response.body().contentType().toString().contains('text/plain')
    response.code() == 200

    checkLogPostExit()
    !logHasErrors
  }

  void 'Multipart Request parameters'(){
    given:
    String url = "http://localhost:${httpPort}/multipart"

    RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
      .addFormDataPart("theFile", "theFileName",
      RequestBody.create(MediaType.parse("text/plain"), "FILE_CONTENT"))
      .addFormDataPart("param1", "param1Value")
      .build()

    Request request = new Request.Builder()
      .url(url)
      .post(requestBody)
      .build()
    when:
    final retValue = client.newCall(request).execute().body().string()

    then:
    retValue == "fileName: theFile"
    hasTainted { tainted ->
      tainted.value == 'theFile' &&
        tainted.ranges[0].source.name == 'Content-Disposition' &&
        tainted.ranges[0].source.origin == 'http.request.multipart.parameter'
    }

  }

  void 'Multipart Request original file name'(){
    given:
    String url = "http://localhost:${httpPort}/multipart"

    RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
      .addFormDataPart("theFile", "theFileName",
      RequestBody.create(MediaType.parse("text/plain"), "FILE_CONTENT"))
      .addFormDataPart("param1", "param1Value")
      .build()

    Request request = new Request.Builder()
      .url(url)
      .post(requestBody)
      .build()
    when:
    final retValue = client.newCall(request).execute().body().string()

    then:
    retValue == "fileName: theFile"
    hasTainted { tainted ->
      tainted.value == 'theFileName' &&
        tainted.ranges[0].source.name == 'filename' &&
        tainted.ranges[0].source.origin == 'http.request.multipart.parameter'
    }

  }


  void 'iast.enabled tag is present'() {
    setup:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasMetric('_dd.iast.enabled', 1)
  }

  void 'weak hash vulnerability is present'() {
    setup:
    String url = "http://localhost:${httpPort}/weakhash"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul ->
      vul.type == 'WEAK_HASH' &&
        vul.evidence.value == 'MD5'
    }
  }

  void 'weak cipher vulnerability is present when calling key generator'() {
    setup:
    String url = "http://localhost:${httpPort}/weak_key_generator"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul ->
      vul.type == 'WEAK_CIPHER' &&
        vul.evidence.value == 'DES'
    }
  }

  void 'weak cipher vulnerability is present when calling key generator with provider'() {
    setup:
    String url = "http://localhost:${httpPort}/weak_key_generator_with_provider"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul ->
      vul.type == 'WEAK_CIPHER' &&
        vul.evidence.value == 'DES'
    }
  }


  void 'insecure cookie vulnerability is present'() {
    setup:
    String url = "http://localhost:${httpPort}/insecure_cookie"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isSuccessful()
    response.header('Set-Cookie').contains('user-id')
    hasVulnerability { vul ->
      vul.type == 'INSECURE_COOKIE' &&
        vul.evidence.value == 'user-id'
    }
  }

  void 'hsts header missing vulnerability is present'() {
    setup:
    String url = "http://localhost:${httpPort}/hstsmissing"
    def request = new Request.Builder().url(url).header("X-Forwarded-Proto", "https").get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isSuccessful()
    hasVulnerability { vul ->
      vul.type == 'HSTS_HEADER_MISSING'
    }
  }

  void 'X content type options missing header vulnerability is present'() {
    setup:
    String url = "http://localhost:${httpPort}/xcontenttypeoptionsmissing"
    def request = new Request.Builder().url(url).get().build()
    when:
    def response = client.newCall(request).execute()
    then:
    response.isSuccessful()
    hasVulnerability { vul ->
      vul.type == 'XCONTENTTYPE_HEADER_MISSING'
    }
  }

  void 'X content type options missing header vulnerability is absent'() {
    setup:
    String url = "http://localhost:${httpPort}/xcontenttypeoptionsecure"
    def request = new Request.Builder().url(url).get().build()
    when:
    def response = client.newCall(request).execute()
    then:
    response.isSuccessful()
    noVulnerability { vul ->
      vul.type == 'XCONTENTTYPE_HEADER_MISSING'
    }
  }


  void 'no HttpOnly cookie vulnerability is present'() {
    setup:
    String url = "http://localhost:${httpPort}/insecure_cookie"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isSuccessful()
    response.header('Set-Cookie').contains('user-id')
    hasVulnerability { vul ->
      vul.type == 'NO_HTTPONLY_COOKIE' &&
        vul.evidence.value == 'user-id'
    }
  }

  void 'no SameSite cookie vulnerability is present'() {
    setup:
    String url = "http://localhost:${httpPort}/insecure_cookie"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isSuccessful()
    response.header('Set-Cookie').contains('user-id')
    hasVulnerability { vul ->
      vul.type == 'NO_SAMESITE_COOKIE' &&
        vul.evidence.value == 'user-id'
    }
  }

  void 'insecure cookie  vulnerability from addheader is present'() {
    setup:
    String url = "http://localhost:${httpPort}/insecure_cookie_from_header"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.isSuccessful()
    response.header('Set-Cookie').contains('user-id')
    hasVulnerability { vul ->
      vul.type == 'INSECURE_COOKIE' &&
        vul.evidence.value == 'user-id'
    }
  }


  void 'weak hash vulnerability is present on boot'() {
    setup:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()

    when: 'ensure the controller is loaded'
    client.newCall(request).execute()

    then: 'a vulnerability pops in the logs (startup traces might not always be available)'
    hasVulnerabilityInLogs { vul ->
      vul.type == 'WEAK_HASH' &&
        vul.evidence.value == 'SHA1' &&
        vul.location.spanId > 0
    }
  }

  void 'weak hash vulnerability is present on thread'() {
    setup:
    String url = "http://localhost:${httpPort}/async_weakhash"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul ->
      vul.type == 'WEAK_HASH' &&
        vul.evidence.value == 'MD4' &&
        vul.location.spanId > 0
    }
  }

  void 'getParameter taints string'() {
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

  void 'command injection is present with runtime'() {
    setup:
    final url = "http://localhost:${httpPort}/cmdi/runtime?cmd=ls"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'COMMAND_INJECTION' }
  }

  void 'command injection is present with process builder'() {
    setup:
    final url = "http://localhost:${httpPort}/cmdi/process_builder?cmd=ls"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'COMMAND_INJECTION' }
  }

  void 'xpath injection is present when compile expression'() {
    setup:
    final url = "http://localhost:${httpPort}/xpathi/compile?expression=%2Fbookstore%2Fbook%2Ftitle"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'XPATH_INJECTION' }
  }


  void 'xpath injection is present when evaluate expression'() {
    setup:
    final url = "http://localhost:${httpPort}/xpathi/evaluate?expression=%2Fbookstore%2Fbook%2Ftitle"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'XPATH_INJECTION' }
  }

  void 'trust boundary violation is present'() {
    setup:
    final url = "http://localhost:${httpPort}/trust_boundary_violation?paramValue=test"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'TRUST_BOUNDARY_VIOLATION' }
  }

  void 'xss is present'() {
    setup:
    final url = "http://localhost:${httpPort}/xss/${method}?string=${param}"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'XSS' && vul.location.method == method }

    where:
    method     | param
    'write'    | 'test'
    'write2'   | 'test'
    'write3'   | 'test'
    'write4'   | 'test'
    'print'    | 'test'
    'print2'   | 'test'
    'println'  | 'test'
    'println2' | 'test'
    'printf'   | 'Formatted%20like%3A%20%251%24s%20and%20%252%24s.'
    'printf2'  | 'test'
    'printf3'  | 'Formatted%20like%3A%20%251%24s%20and%20%252%24s.'
    'printf4'  | 'test'
    'format'   | 'Formatted%20like%3A%20%251%24s%20and%20%252%24s.'
    'format2'  | 'test'
    'format3'  | 'Formatted%20like%3A%20%251%24s%20and%20%252%24s.'
    'format4'  | 'test'
    'responseBody' | 'test'
  }

  void 'trust boundary violation with cookie propagation'() {
    setup:
    final url = "http://localhost:${httpPort}/trust_boundary_violation_for_cookie"
    final request = new Request.Builder().url(url).get().addHeader("Cookie", "https%3A%2F%2Fuser-id2=https%3A%2F%2Fkkk").build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'TRUST_BOUNDARY_VIOLATION' }
    hasTainted { tainted ->
      tainted.value == 'https%3A%2F%2Fuser-id2' &&
        tainted.ranges[0].source.origin == 'http.request.cookie.name'
    }
    hasTainted { tainted ->
      tainted.value == 'https://kkk' &&
        tainted.ranges[0].source.origin == 'http.request.cookie.value'
    }
  }


  void 'path traversal is present with file'() {
    setup:
    final url = "http://localhost:${httpPort}/path_traversal/file?path=test"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'PATH_TRAVERSAL' }
  }

  void 'path traversal is present with paths'() {
    setup:
    final url = "http://localhost:${httpPort}/path_traversal/paths?path=test"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'PATH_TRAVERSAL' }
  }

  void 'path traversal is present with path'() {
    setup:
    final url = "http://localhost:${httpPort}/path_traversal/path?path=test"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'PATH_TRAVERSAL' }
  }

  void 'parameter binding taints bean strings'() {
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

  void 'getRequestURL taints its output'() {
    setup:
    String url = "http://localhost:${httpPort}/getrequesturl"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == url &&
        tainted.ranges[0].source.origin == 'http.request.uri'
    }
  }

  void 'request header taint string'() {
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

  void 'path param taint string'() {
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

  void 'request body taint json'() {
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

  @SuppressWarnings('CyclomaticComplexity')
  void 'tainting of path variables — RequestMappingInfoHandlerMapping variant'() {
    given:
    String url = "http://localhost:${httpPort}/matrix/value1;xxx=aaa,bbb;yyy=ccc/value2;zzz=ddd"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      final firstRange = tainted.ranges[0]
      tainted.value == 'value1' &&
        firstRange?.source?.origin == 'http.request.path.parameter' &&
        firstRange?.source?.name == 'var1'
    }
    ['xxx', 'aaa', 'bbb', 'yyy', 'ccc'].each {
      hasTainted { tainted ->
        final firstRange = tainted.ranges[0]
        tainted.value == it &&
          firstRange?.source?.origin == 'http.request.matrix.parameter' &&
          firstRange?.source?.name == 'var1'
      }
    }
    hasTainted { tainted ->
      final firstRange = tainted.ranges[0]
      tainted.value == 'value2' &&
        firstRange?.source?.origin == 'http.request.path.parameter' &&
        firstRange?.source?.name == 'var2'
    }
    ['zzz', 'ddd'].each {
      hasTainted { tainted ->
        final firstRange = tainted.ranges[0]
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
    hasVulnerability { vul -> vul.type == 'SSRF' }
  }

  void 'test iast metrics stored in spans'() {
    setup:
    final url = "http://localhost:${httpPort}/cmdi/runtime?cmd=ls"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasMetric('_dd.iast.telemetry.executed.sink.command_injection', 1)
  }

  void 'weak randomness is present in #evidence'() {
    setup:
    final url = "http://localhost:${httpPort}/weak_randomness?mode=${evidence}"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'WEAK_RANDOMNESS' && vul.evidence.value == evidence }

    where:
    evidence                                 | _
    'java.util.Random'                       | _
    'java.util.concurrent.ThreadLocalRandom' | _
    'java.lang.Math'                         | _
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
    client.newCall(request).execute()

    then:
    hasVulnerabilityInLogs { vul -> vul.type == 'UNVALIDATED_REDIRECT' && vul.location.method == 'unvalidatedRedirectForwardFromString' }
  }

  def "get View from tainted string"() {
    setup:
    String url = "http://localhost:${httpPort}/get_view_from_tainted_string?param=redirected"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerabilityInLogs { vul -> vul.type == 'UNVALIDATED_REDIRECT' && vul.location.method == 'getViewfromTaintedString' }
  }

  void 'getRequestURI taints its output'() {
    setup:
    final url = "http://localhost:${httpPort}/getrequesturi"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == '/getrequesturi' &&
        tainted.ranges[0].source.origin == 'http.request.path'
    }
  }

  void 'header injection'(){
    setup:
    final url = "http://localhost:${httpPort}/header_injection?param=test"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'HEADER_INJECTION' }
  }

  void 'header injection exclusion'(){
    setup:
    final url = "http://localhost:${httpPort}/header_injection_exclusion?param=testExclusion"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    noVulnerability { vul -> vul.type == 'HEADER_INJECTION'}
  }

  void 'header injection redaction'(){
    setup:
    String bearer = URLEncoder.encode("Authorization: bearer 12345644", "UTF-8")
    final url = "http://localhost:${httpPort}/header_injection_redaction?param=" + bearer
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'HEADER_INJECTION' && vul.evidence.valueParts[1].redacted == true }
  }

}
