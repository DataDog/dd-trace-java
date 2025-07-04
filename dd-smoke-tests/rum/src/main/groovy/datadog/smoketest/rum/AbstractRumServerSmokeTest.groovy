package datadog.smoketest.rum

import datadog.smoketest.AbstractServerSmokeTest
import okhttp3.Response
import spock.lang.Shared

class AbstractRumServerSmokeTest extends AbstractServerSmokeTest {
  @Shared
  protected String[] defaultRumProperties = [
    "-Ddd.rum.enabled=true",
    "-Ddd.rum.application.id=appid",
    "-Ddd.rum.client.token=token"
  ]


  static void assertRumInjected(Response response) {
    assert response.header('x-datadog-rum-injected') == '1': 'RUM injected header missing'
    def content = response.body().string()
    assert content.contains('https://www.datadoghq-browser-agent.com'): 'RUM script not injected'
  }
}
