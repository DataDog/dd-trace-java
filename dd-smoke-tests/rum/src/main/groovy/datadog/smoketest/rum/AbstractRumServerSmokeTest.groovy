package datadog.smoketest.rum

import datadog.smoketest.AbstractServerSmokeTest
import okhttp3.Request
import okhttp3.Response
import spock.lang.Shared

class AbstractRumServerSmokeTest extends AbstractServerSmokeTest {
  @Shared
  protected String[] defaultRumProperties = [
    "-Ddd.rum.enabled=true",
    "-Ddd.rum.application.id=appid",
    "-Ddd.rum.client.token=token",
    "-Ddd.rum.remote.configuration.id=12345",
  ]

  String mountPoint() {
    ""
  }

  void 'test RUM SDK injection on html for path #servletPath'() {
    given:
    def url = "http://localhost:${httpPort}${mountPoint()}/${servletPath}"
    def request = new Request.Builder()
      .url(url)
      .get()
      .build()

    when:
    Response response = client.newCall(request).execute()

    then:
    response.code() == 200
    assertRumInjected(response)
    where:
    servletPath << ["html", "html_async"]
  }

  void 'test RUM SDK injection skip on unsupported mime type'() {
    given:
    def url = "http://localhost:${httpPort}${mountPoint()}/xml"
    def request = new Request.Builder()
      .url(url)
      .get()
      .build()

    when:
    Response response = client.newCall(request).execute()

    then:
    response.code() == 200
    assertRumNotInjected(response)
  }

  static void assertRumInjected(Response response) {
    assert response.header('x-datadog-rum-injected') == '1': 'RUM injected header missing'
    def content = response.body().string()
    assert content.contains('https://www.datadoghq-browser-agent.com'): 'RUM script not injected'
    assert content.trim().endsWith('</html>'): 'Response not fully flushed'
    assert content.indexOf("DD_RUM.init(") == content.lastIndexOf("DD_RUM.init("): 'script injected more than once'
  }

  static void assertRumNotInjected(Response response) {
    assert response.header('x-datadog-rum-injected') == null: 'RUM header unexpectedly injected'
    def content = response.body().string()
    assert !content.contains('https://www.datadoghq-browser-agent.com'): 'RUM script unexpectedly injected'
    assert content.trim().endsWith('</response>'): 'Response not fully flushed'
  }
}
