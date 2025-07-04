package datadog.smoketest.rum

import datadog.smoketest.AbstractServerSmokeTest
import okhttp3.Response
import spock.lang.Shared

class AbstractRumServerSmokeTest extends AbstractServerSmokeTest {



  @Shared
  protected String[] defaultRumProperties = [
    "-Ddd.appsec.enabled=${System.getProperty('smoke_test.appsec.enabled') ?: 'true'}",
    "-Ddd.profiling.enabled=false",
    // TODO: Remove once this is the default value
    "-Ddd.api-security.enabled=true",
    "-Ddd.appsec.waf.timeout=300000",
    "-DPOWERWAF_EXIT_ON_LEAK=true",
    // disable AppSec rate limit
    "-Ddd.appsec.trace.rate.limit=-1"
  ] + (System.getProperty('smoke_test.appsec.enabled') == 'inactive' ?
    // enable remote config so that appsec is partially enabled (rc is now enabled by default)
    [
      '-Ddd.remote_config.url=https://127.0.0.1:54670/invalid_endpoint',
      '-Ddd.remote_config.poll_interval.seconds=3600'
    ]:
    ['-Ddd.remote_config.enabled=false']
  )


  static void assertRumInjected(Response response) {
    assert response.header('x-datadog-rum-injected') == '1' : 'RUM injected header missing'
    def content = response.body().toString()
    assert content.contains('https://www.datadoghq-browser-agent.com') : 'RUM script not injected'
  }


}
