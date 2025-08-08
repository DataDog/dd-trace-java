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

  void 'test RUM SDK injection on html'() {
    given:
    def url = "http://localhost:${httpPort}/html"
    def request = new Request.Builder()
      .url(url)
      .get()
      .build()

    when:
    Response response = client.newCall(request).execute()

    then:
    response.code() == 200
    assertRumInjected(response)
  }

  void 'test RUM SDK injection skip on unsupported mime type'() {
    given:
    def url = "http://localhost:${httpPort}/xml"
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
    assert content.endsWith('</html>'): 'Response not fully flushed'
  }

  static void assertRumNotInjected(Response response) {
    assert response.header('x-datadog-rum-injected') == null: 'RUM header unexpectedly injected'
    def content = response.body().string()
    System.err.println(content)
    assert !content.contains('https://www.datadoghq-browser-agent.com'): 'RUM script unexpectedly injected'
    assert content.endsWith('</response>')
  }
}
