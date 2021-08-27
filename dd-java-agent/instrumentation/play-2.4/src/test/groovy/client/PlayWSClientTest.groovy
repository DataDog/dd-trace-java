package client

import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.instrumentation.netty40.client.NettyHttpClientDecorator
import play.libs.ws.WS
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Subject
import spock.lang.Timeout

// Play 2.6+ uses a separately versioned client that shades the underlying dependency
// This means our built in instrumentation won't work.
@Timeout(5)
class PlayWSClientTest extends HttpClientTest {
  @Subject
  @Shared
  @AutoCleanup
  def client = WS.newClient(-1)

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }

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
  String expectedOperationName() {
    return "netty.client.request"
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
