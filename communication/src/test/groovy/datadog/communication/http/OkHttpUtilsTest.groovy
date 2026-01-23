package datadog.communication.http

import okhttp3.Call
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import spock.lang.Shared
import spock.lang.Specification

class OkHttpUtilsTest extends Specification {

  @Shared
  MockWebServer server = new MockWebServer()

  def cleanupSpec() {
    server.shutdown()
  }

  def "It is possible to register a custom listener for HTTP requests"() {
    setup:
    def url = server.url("/")
    def client = HttpUtils.buildHttpClient(url, 1000)
    def listener = new TestListener()

    server.enqueue(new MockResponse())

    when:
    def request = new Request.Builder()
      .url(url)
      .get()
      .tag(HttpUtils.CustomListener, listener)
      .build()
    client.newCall(request).execute()

    then:
    listener.notified
  }

  private static final class TestListener extends HttpUtils.CustomListener {
    private boolean notified

    void callStart(Call call) {
      notified = true
    }
  }
}
