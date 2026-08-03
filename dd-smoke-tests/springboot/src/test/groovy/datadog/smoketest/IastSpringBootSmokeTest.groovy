package datadog.smoketest

import datadog.environment.JavaVirtualMachine
import datadog.trace.api.config.IastConfig
import datadog.trace.test.util.Flaky
import groovy.transform.CompileDynamic
import okhttp3.Request
import okhttp3.Response

import static datadog.trace.api.iast.IastContext.Mode.GLOBAL

@CompileDynamic
class IastSpringBootSmokeTest extends AbstractIastSpringBootTest {

  void 'tainting of jwt'() {
    given:
    String url = "http://localhost:${httpPort}/jwt"
    String token = 'Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJqYWNraWUiLCJpc3MiOiJtdm5zZWFyY2gifQ.C_q7_FwlzmvzC6L3CqOnUzb6PFs9REZ3RON6_aJTxWw'
    def request = new Request.Builder().url(url).header('Authorization', token).get().build()

    when:
    Response response = client.newCall(request).execute()

    then:
    response.successful
    response.body().string().contains('jackie')

    hasTainted {
      it.value == 'jackie' &&
      it.ranges[0].source.origin == 'http.request.header'
    }
  }

  void 'code injection is present (string)'() {
    given:
    final param = 'test'
    final url = "http://localhost:${httpPort}/code_injection/beanshell?param=${param}"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability {
      vul ->
      vul.type == 'CODE_INJECTION'
      && vul.location.method == 'beanshell'
      && vul.evidence.valueParts.size() == 1
      && vul.evidence.valueParts[0].value == param
      && vul.evidence.valueParts[0].source.origin == 'http.request.parameter'
    }
  }

  void 'code injection is present (reader)'() {
    given:
    final param = 'test'
    final url = "http://localhost:${httpPort}/code_injection/beanshell_reader?param=${param}"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    // The reader is tainted as an object, so the evidence value is the reader identity rather than
    // the script; assert the type, location and source origin instead of the value.
    hasVulnerability {
      vul ->
      vul.type == 'CODE_INJECTION'
      && vul.location.method == 'beanshellReader'
      && vul.evidence.valueParts[0].source.origin == 'http.request.parameter'
    }
  }

  void 'code injection is present (remote)'() {
    given:
    final script = 'test'
    final ssrfUrl = 'http://localhost:1/'
    final url = "http://localhost:${httpPort}/code_injection/beanshell_remote?" +
    "url=${URLEncoder.encode(ssrfUrl, 'UTF-8')}&script=${script}"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then: 'the script is reported as a code injection'
    hasVulnerability {
      vul ->
      vul.type == 'CODE_INJECTION'
      && vul.location.method == 'beanshellRemote'
      && vul.evidence.valueParts.size() == 1
      && vul.evidence.valueParts[0].value == script
      && vul.evidence.valueParts[0].source.origin == 'http.request.parameter'
    }

    and: 'the url is reported as an ssrf'
    hasVulnerability {
      vul ->
      vul.type == 'SSRF'
      && vul.evidence.valueParts.size() == 1
      && vul.evidence.valueParts[0].value == ssrfUrl
      && vul.evidence.valueParts[0].source.origin == 'http.request.parameter'
    }
  }

  void 'find hardcoded secret'() {
    given:
    String url = "http://localhost:${httpPort}/hardcodedSecret"

    when:
    Response response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    response.successful
    isLogPresent {
      String log ->
      def vulns = parseVulnerabilitiesLog(log)
      vulns.any {
        vul ->
        vul.type == 'HARDCODED_SECRET'
        && vul.location.method == 'hardcodedSecret'
        && vul.location.path == 'datadog.smoketest.springboot.controller.HardcodedSecretController'
        && vul.location.line == 11
        && vul.evidence.value == 'age-secret-key'
      }
    }
  }

  @Flaky(value = 'global context is flaky under IBM8', condition = () -> JavaVirtualMachine.isIbm8())
  static class WithGlobalContext extends IastSpringBootSmokeTest {
    @Override
    protected List<String> iastJvmOpts() {
      final opts = super.iastJvmOpts()
      opts.add(withSystemProperty(IastConfig.IAST_CONTEXT_MODE, GLOBAL.name()))
      return opts
    }
  }
}
