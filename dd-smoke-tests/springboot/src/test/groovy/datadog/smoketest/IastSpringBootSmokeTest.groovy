package datadog.smoketest

import datadog.trace.api.config.IastConfig
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

  void 'find hardcoded secret'() {
    given:
    String url = "http://localhost:${httpPort}/hardcodedSecret"

    when:
    Response response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    response.successful
    hasVulnerabilityInLogs {
      vul ->
      vul.type == 'HARDCODED_SECRET'
      && vul.location.method == '<init>'
      && vul.location.path == 'datadog.smoketest.springboot.controller.IastWebController'
      && vul.location.line == 57
      && vul.evidence.value == 'age-secret-key'
    }
  }

  static class WithGlobalContext extends IastSpringBootSmokeTest {
    @Override
    protected List<String> iastJvmOpts() {
      final opts = super.iastJvmOpts()
      opts.add(withSystemProperty(IastConfig.IAST_CONTEXT_MODE, GLOBAL.name()))
      return opts
    }
  }
}
