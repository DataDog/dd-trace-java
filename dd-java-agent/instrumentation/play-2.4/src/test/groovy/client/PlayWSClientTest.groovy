package client

import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingNettyHttpNamingConventions
import datadog.trace.instrumentation.netty40.client.NettyHttpClientDecorator
import play.libs.ws.WS
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Subject
import spock.lang.Timeout

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
    return false
  }
}

class PlayWSClientV0ForkedTest extends PlayWSClientTest implements TestingNettyHttpNamingConventions.ClientV0  {
}

class PlayWSClientV1ForkedTest extends PlayWSClientTest implements TestingNettyHttpNamingConventions.ClientV1{
}
