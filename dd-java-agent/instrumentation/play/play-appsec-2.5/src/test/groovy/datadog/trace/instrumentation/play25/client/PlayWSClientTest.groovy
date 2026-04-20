package datadog.trace.instrumentation.play25.client

import datadog.trace.agent.test.asserts.TagsAssert
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingNettyHttpNamingConventions
import play.libs.ws.WS
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Subject
import spock.lang.Timeout

import java.util.concurrent.TimeoutException

// Play 2.6+ uses a separately versioned client that shades the underlying dependency
// This means our built in instrumentation won't work.
@Timeout(5)
abstract class PlayWSClientTest extends HttpClientTest {
  @Subject
  @Shared
  @AutoCleanup
  def client = WS.newClient(-1)

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def request = client.url(uri.toString())
    headers.entrySet().each {
      request.setHeader(it.key, it.value)
    }

    def status = request.execute(method).thenApply {
      callback?.call()
      it
    }.thenApply {
      it.status
    }
    return status.toCompletableFuture().get()
  }

  @Override
  String component() {
    'netty-client'
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
    return false
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

class PlayWSClientV0ForkedTest extends PlayWSClientTest implements TestingNettyHttpNamingConventions.ClientV0  {
}

class PlayWSClientV1ForkedTest extends PlayWSClientTest implements TestingNettyHttpNamingConventions.ClientV1{
}
