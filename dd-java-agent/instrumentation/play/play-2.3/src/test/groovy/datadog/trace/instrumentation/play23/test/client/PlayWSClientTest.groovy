package datadog.trace.instrumentation.play23.test.client

import datadog.trace.agent.test.asserts.TagsAssert
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingNettyHttpNamingConventions
import datadog.trace.instrumentation.netty38.client.NettyHttpClientDecorator
import play.GlobalSettings
import play.libs.ws.WS
import play.test.FakeApplication
import play.test.Helpers
import spock.lang.Shared

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class PlayWSClientTest extends HttpClientTest implements TestingNettyHttpNamingConventions.ClientV0 {
  @Shared
  def application = new FakeApplication(
  new File("."),
  FakeApplication.getClassLoader(),
  [
    "ws.timeout.connection": CONNECT_TIMEOUT_MS,
    "ws.timeout.request"   : READ_TIMEOUT_MS
  ],
  Collections.emptyList(),
  new GlobalSettings()
  )

  @Shared
  def client

  def setupSpec() {
    Helpers.start(application)
    client = WS.client()
  }

  def cleanupSpec() {
    Helpers.stop(application)
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def request = client.url(uri.toString())
    headers.entrySet().each {
      request.setHeader(it.key, it.value)
    }

    def status = request.execute(method).map({
      callback?.call()
      it
    }).map({
      it.status
    })
    return status.get(1, TimeUnit.SECONDS)
  }

  @Override
  CharSequence component() {
    return NettyHttpClientDecorator.DECORATE.component()
  }


  @Override
  boolean testRedirects() {
    false
  }

  @Override
  boolean testConnectionFailure() {
    false
  }

  @Override
  boolean testRemoteConnection() {
    // On connection failures the operation and resource names end up different from expected.
    // This would require a lot of changes to the base client test class to support
    // span.operationName = "netty.connect"
    // span.resourceName = "netty.connect"
    false
  }

  @Override
  void assertErrorTags(TagsAssert tagsAssert, Throwable exception) {
    // PlayWS classes throw different exception types for the same connection failures
    if (exception instanceof ConnectException ||
      exception instanceof SocketTimeoutException ||
      exception instanceof TimeoutException) {
      tagsAssert.tag("error.type", {
        String actualType = it as String
        return actualType == "java.net.ConnectException" ||
          actualType == "java.net.SocketTimeoutException" ||
          actualType == "java.util.concurrent.TimeoutException"
      })
      tagsAssert.tag("error.stack", String)
      tagsAssert.tag("error.message", String)
    } else {
      super.assertErrorTags(tagsAssert, exception)
    }
  }
}
