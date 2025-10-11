package datadog.smoketest

import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_SECURITY_CONTROLS_CONFIGURATION

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
      withSystemProperty(IAST_SECURITY_CONTROLS_CONFIGURATION, "SANITIZER:XSS:ddtest.securitycontrols.Sanitizer:sanitize;INPUT_VALIDATOR:XSS:ddtest.securitycontrols.InputValidator:validateAll;INPUT_VALIDATOR:XSS:ddtest.securitycontrols.InputValidator:validate:java.lang.Object,java.lang.String,java.lang.String:1,2"),
    ]
  }

  @Override
  boolean isErrorLog(String log) {
    if (log.contains('no such algorithm: DES for provider SUN')) {
      return false
    }

    if (super.isErrorLog(log) || log.contains('Not starting IAST subsystem')) {
      return true
    }

    // Check that there's no logged exception about missing classes from Datadog.
    // We had this problem before with JDK9StackWalker.
    return log.contains('java.lang.ClassNotFoundException: datadog/')
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
  }

  void 'Multipart Request parameters'() {
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

  void 'Tainted mail Text Jakarta'() {
    given:
    String url = "http://localhost:${httpPort}/jakartaMailHtmlVulnerability"
    String messageText = "This is a test message"
    RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
    .addFormDataPart("messageText", messageText)
    .addFormDataPart("sanitize", "false")
    .build()
    Request request = new Request.Builder()
    .url(url)
    .post(requestBody)
    .build()

    when:
    client.newCall(request).execute().body().string()

    then:
    hasVulnerability { vulnerability ->
      vulnerability.type == 'EMAIL_HTML_INJECTION'
    }
  }


  void 'Tainted mail Content Jakarta'() {
    given:
    String url = "http://localhost:${httpPort}/jakartaMailHtmlVulnerability"
    String messageContent = "<html><body><h1>This is a test message</h1></body></html>"
    RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
    .addFormDataPart("messageContent", messageContent).build()
    Request request = new Request.Builder()
    .url(url)
    .post(requestBody)
    .build()

    when:
    client.newCall(request).execute().body().string()

    then:
    hasVulnerability { vulnerability ->
      vulnerability.type == 'EMAIL_HTML_INJECTION'
    }
  }

  void 'Sanitized mail Content Jakarta'() {
    given:
    String url = "http://localhost:${httpPort}/jakartaMailHtmlVulnerability"
    String messageContent = "<html><body><h1>This is a test message</h1></body></html>"
    RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
    .addFormDataPart("messageContent", messageContent)
    .addFormDataPart("sanitize", "true")
    .build()
    Request request = new Request.Builder()
    .url(url)
    .post(requestBody)
    .build()

    when:
    client.newCall(request).execute().body().string()

    then:
    noVulnerability { vulnerability ->
      vulnerability.type == 'EMAIL_HTML_INJECTION'
    }
  }

  void 'Multipart Request original file name'() {
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

  void 'vulnerabilities have stacktrace'(){
    setup:
    final url = "http://localhost:${httpPort}/cmdi/runtime?cmd=ls"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerabilityStack()
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
    def resp = client.newCall(request).execute()

    then:
    resp.code() == 200
    resp.close()

    and: 'a vulnerability pops in the logs (startup traces might not always be available)'
    isLogPresent { String log ->
      def vulns = parseVulnerabilitiesLog(log)
      vulns.any { vul ->
        vul.type == 'WEAK_HASH' &&
        vul.evidence.value == 'SHA1' &&
        vul.location.spanId > 0
      }
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
    method         | param
    'write'        | 'test'
    'write2'       | 'test'
    'write3'       | 'test'
    'write4'       | 'test'
    'print'        | 'test'
    'print2'       | 'test'
    'println'      | 'test'
    'println2'     | 'test'
    'printf'       | 'Formatted%20like%3A%20%251%24s%20and%20%252%24s.'
    'printf2'      | 'test'
    'printf3'      | 'Formatted%20like%3A%20%251%24s%20and%20%252%24s.'
    'printf4'      | 'test'
    'format'       | 'Formatted%20like%3A%20%251%24s%20and%20%252%24s.'
    'format2'      | 'test'
    'format3'      | 'Formatted%20like%3A%20%251%24s%20and%20%252%24s.'
    'format4'      | 'test'
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
    final url = "http://localhost:${httpPort}/ssrf${path}"
    final body = new FormBody.Builder().add(parameter, value).build()
    final request = new Request.Builder().url(url).post(body).build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul ->
      if (vul.type != 'SSRF') {
        return false
      }
      final parts = vul.evidence.valueParts
      if (parameter == 'url') {
        return parts.size() == 1
        && parts[0].value == value && parts[0].source.origin == 'http.request.parameter' && parts[0].source.name == parameter
      }

      if (parameter == 'host') {
        return parts.size() == 3
        && parts[0].value == 'https://' && parts[0].source == null
        && parts[1].value == value && parts[1].source.origin == 'http.request.parameter' && parts[1].source.name == parameter
        && parts[2].value == ':443/test' && parts[2].source == null
      }

      throw new IllegalArgumentException("Parameter $parameter not supported")
    }

    where:
    path   | parameter | value
    ''     | 'url'     | 'https://dd.datad0g.com/'
    ''     | 'host'    | 'dd.datad0g.com'
    '/uri' | 'url'     | 'https://dd.datad0g.com/'
    '/uri' | 'host'    | 'dd.datad0g.com'
  }

  void 'ssrf is present (#path) (#parameter)'() {
    setup:
    final url = "http://localhost:${httpPort}/ssrf/${path}"
    final body = new FormBody.Builder().add(parameter, value).build()
    final request = new Request.Builder().url(url).post(body).build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul ->
      if (vul.type != 'SSRF') {
        return false
      }
      final parts = vul.evidence.valueParts
      if (parameter == 'url') {
        return parts.size() == 1
        && parts[0].value == value && parts[0].source.origin == 'http.request.parameter' && parts[0].source.name == parameter
      }

      if (parameter == 'host') {
        String protocol = protocolSecure ? 'https://' : 'http://'
        String finalValue = protocol + value + (endSlash ? '/' : '')
        return parts[0].value.endsWith(finalValue) && parts[0].source.origin == 'http.request.parameter' && parts[0].source.name == parameter
      }

      if (parameter == 'urlProducer' || parameter == 'urlHandler') {
        return parts.size() == 1
        && parts[0].value.endsWith(value) && parts[0].source.origin == 'http.request.parameter' && parts[0].source.name == parameter
      }

      throw new IllegalArgumentException("Parameter $parameter not supported")
    }

    where:
    path                     | parameter     | value                     | protocolSecure | endSlash
    "apache-httpclient4"     | "url"         | "https://dd.datad0g.com/" | true           | true
    "apache-httpclient4"     | "host"        | "dd.datad0g.com"          | false          | false
    "apache-httpasyncclient" | "url"         | "https://dd.datad0g.com/" | true           | true
    "apache-httpasyncclient" | "urlProducer" | "https://dd.datad0g.com/" | true           | true
    "apache-httpasyncclient" | "host"        | "dd.datad0g.com"          | false          | false
    "apache-httpclient5"     | "url"         | "https://dd.datad0g.com/" | true           | true
    "apache-httpclient5"     | "urlHandler"  | "https://dd.datad0g.com/" | true           | true
    "apache-httpclient5"     | "host"        | "dd.datad0g.com"          | false          | true
    "commons-httpclient2"    | "url"         | "https://dd.datad0g.com/" | true           | true
    "okHttp2"                | "url"         | "https://dd.datad0g.com/" | true           | true
    "okHttp3"                | "url"         | "https://dd.datad0g.com/" | true           | true
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

  void 'header injection'() {
    setup:
    final url = "http://localhost:${httpPort}/header_injection?param=test"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'HEADER_INJECTION' }
  }

  void 'header injection exclusion'() {
    setup:
    final url = "http://localhost:${httpPort}/header_injection_exclusion?param=testExclusion"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    noVulnerability { vul -> vul.type == 'HEADER_INJECTION' }
  }

  void 'header injection redaction'() {
    setup:
    String bearer = URLEncoder.encode("Authorization: bearer 12345644", "UTF-8")
    final url = "http://localhost:${httpPort}/header_injection_redaction?param=" + bearer
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'HEADER_INJECTION' && vul.evidence.valueParts[1].redacted == true }
  }

  void 'Insecure Auth Protocol vulnerability is present'() {
    setup:
    String url = "http://localhost:${httpPort}/insecureAuthProtocol"
    def request = new Request.Builder().url(url).header("Authorization", "Basic YWxhZGRpbjpvcGVuc2VzYW1l").get().build()
    when:
    def response = client.newCall(request).execute()
    then:
    response.isSuccessful()
    hasVulnerability { vul ->
      vul.type == 'INSECURE_AUTH_PROTOCOL'
    }
  }

  void "Check reflection injection forName"() {
    setup:
    String url = "http://localhost:${httpPort}/reflection_injection/class?param=java.lang.String"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability {
      vul ->
      vul.type == 'REFLECTION_INJECTION'
      && vul.location.method == 'reflectionInjectionClass'
      && vul.evidence.valueParts[0].value == "java.lang.String"
    }
  }

  void "Check reflection injection getMethod"() {
    setup:
    String url = "http://localhost:${httpPort}/reflection_injection/method?param=isEmpty"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability {
      vul ->
      vul.type == 'REFLECTION_INJECTION'
      && vul.location.method == 'reflectionInjectionMethod'
      && vul.evidence.valueParts[0].value == "java.lang.String#"
      && vul.evidence.valueParts[1].value == "isEmpty"
      && vul.evidence.valueParts[2].value == "()"
    }
  }

  void "Check reflection injection getField"() {
    setup:
    String url = "http://localhost:${httpPort}/reflection_injection/field?param=hash"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability {
      vul ->
      vul.type == 'REFLECTION_INJECTION'
      && vul.location.method == 'reflectionInjectionField'
      && vul.evidence.valueParts[0].value == "java.lang.String#"
      && vul.evidence.valueParts[1].value == "hash"
    }
  }

  void "Check reflection injection lookup"() {
    setup:
    String url = "http://localhost:${httpPort}/reflection_injection/lookup?param=hash"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability {
      vul ->
      vul.type == 'REFLECTION_INJECTION'
      && vul.location.method == 'reflectionInjectionLookup'
      && vul.evidence.valueParts[0].value == "java.lang.String#"
      && vul.evidence.valueParts[1].value == "hash"
    }
  }

  void 'find session rewriting'() {
    given:
    String url = "http://localhost:${httpPort}/greeting"

    when:
    Response response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    response.successful
    // Vulnerability may have been detected in a previous request instead, check the full logs.
    isLogPresent { String log ->
      def vulns = parseVulnerabilitiesLog(log)
      vulns.any { it.type == 'SESSION_REWRITING' }
    }
  }

  void 'untrusted deserialization for an input stream'() {
    setup:
    final url = "http://localhost:${httpPort}/untrusted_deserialization"
    ByteArrayOutputStream baos = new ByteArrayOutputStream()
    ObjectOutputStream oos = new ObjectOutputStream(baos)
    oos.writeObject("This is a test object.")
    RequestBody requestBody = RequestBody.create(MediaType.parse("application/octet-stream"), baos.toByteArray())
    final request = new Request.Builder().url(url).post(requestBody).build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'UNTRUSTED_DESERIALIZATION' }
  }

  void 'untrusted deserialization for a servlet file upload which calls parseRequest'() {
    setup:
    final url = "http://localhost:${httpPort}/untrusted_deserialization/parse_request"
    ByteArrayOutputStream baos = new ByteArrayOutputStream()
    ObjectOutputStream oos = new ObjectOutputStream(baos)
    oos.writeObject("This is a test object.")
    RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
    .addFormDataPart("file", "test.txt",
    RequestBody.create(MediaType.parse("application/octet-stream"), baos.toByteArray()))
    .build()
    final request = new Request.Builder().url(url).post(requestBody).build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'UNTRUSTED_DESERIALIZATION' }
  }

  void 'untrusted deserialization for a servlet file upload which calls parseParameterMap'() {
    setup:
    final url = "http://localhost:${httpPort}/untrusted_deserialization/parse_parameter_map"
    ByteArrayOutputStream baos = new ByteArrayOutputStream()
    ObjectOutputStream oos = new ObjectOutputStream(baos)
    oos.writeObject("This is a test object.")
    RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
    .addFormDataPart("file", "test.txt",
    RequestBody.create(MediaType.parse("application/octet-stream"), baos.toByteArray()))
    .build()
    final request = new Request.Builder().url(url).post(requestBody).build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'UNTRUSTED_DESERIALIZATION' }
  }

  void 'untrusted deserialization for a servlet file upload which calls getItemIterator'() {
    setup:
    final url = "http://localhost:${httpPort}/untrusted_deserialization/get_item_iterator"
    ByteArrayOutputStream baos = new ByteArrayOutputStream()
    ObjectOutputStream oos = new ObjectOutputStream(baos)
    oos.writeObject("This is a test object.")
    RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
    .addFormDataPart("file", "test.txt",
    RequestBody.create(MediaType.parse("application/octet-stream"), baos.toByteArray()))
    .build()
    final request = new Request.Builder().url(url)
    .post(requestBody).build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'UNTRUSTED_DESERIALIZATION' }
  }

  void 'untrusted deserialization for a multipart file'() {
    setup:
    final url = "http://localhost:${httpPort}/untrusted_deserialization/multipart"
    ByteArrayOutputStream baos = new ByteArrayOutputStream()
    ObjectOutputStream oos = new ObjectOutputStream(baos)
    oos.writeObject("This is a test object.")
    RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
    .addFormDataPart("file", "test.txt",
    RequestBody.create(MediaType.parse("application/octet-stream"), baos.toByteArray()))
    .build()
    final request = new Request.Builder().url(url)
    .post(requestBody).build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'UNTRUSTED_DESERIALIZATION' }
  }

  void 'untrusted deserialization for a part'() {
    setup:
    final url = "http://localhost:${httpPort}/untrusted_deserialization/part"
    ByteArrayOutputStream baos = new ByteArrayOutputStream()
    ObjectOutputStream oos = new ObjectOutputStream(baos)
    oos.writeObject("This is a test object.")
    RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
    .addFormDataPart("file", "test.txt",
    RequestBody.create(MediaType.parse("application/octet-stream"), baos.toByteArray()))
    .build()
    final request = new Request.Builder().url(url)
    .post(requestBody).build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'UNTRUSTED_DESERIALIZATION' }
  }

  void 'untrusted deserialization for snakeyaml with a string'() {
    setup:
    final String yaml = "test"
    final url = "http://localhost:${httpPort}/untrusted_deserialization/snakeyaml?yaml=${yaml}"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'UNTRUSTED_DESERIALIZATION' }
  }

  void 'test custom string reader'() {
    setup:
    final url = "http://localhost:${httpPort}/test_custom_string_reader?param=Test"
    final request = new Request.Builder().url(url).get().build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.body().string().contains("Test")
  }
  void 'security controls avoid vulnerabilities'() {
    setup:
    final url = "http://localhost:${httpPort}/xss/${method}?string=test&string2=test2"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    noVulnerability { vul -> vul.type == 'XSS' && vul.location.method == method }

    where:
    method         |  _
    'sanitize'        | _
    'validateAll'       | _
    'validateAll2'     | _
    'validate'     | _
  }
}
