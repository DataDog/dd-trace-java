import datadog.trace.agent.test.AgentTestRunner
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import org.asynchttpclient.AsyncCompletionHandler
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.BoundRequestBuilder
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.asynchttpclient.Response
import spock.lang.AutoCleanup
import spock.lang.Shared

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.Checkpointer.CPU
import static datadog.trace.api.Checkpointer.END
import static datadog.trace.api.Checkpointer.SPAN
import static datadog.trace.api.Checkpointer.THREAD_MIGRATION
import static org.asynchttpclient.Dsl.asyncHttpClient

class NettyClientCheckpointsTest extends AgentTestRunner {

  @AutoCleanup
  @Shared
  def server = httpServer {
    handlers {
      prefix("/") {
        handleDistributedRequest()
        response.status(200).send("hello")
      }
    }
  }

  def clientConfig = DefaultAsyncHttpClientConfig.Builder.newInstance()
  .setConnectTimeout(5000)
  .setRequestTimeout(5000)
  .setReadTimeout(5000)
  .setSslContext(SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build())
  .setMaxRequestRetry(0)

  // Can't be @Shared otherwise field-injected classes get loaded too early.
  @AutoCleanup
  AsyncHttpClient asyncHttpClient = asyncHttpClient(clientConfig)

  def "emit checkpoints"() {
    when:
    runUnderTrace("parent") {
      executeRequest("GET", server.address, [:])
    }
    // note that the test http server is also traced and needs to be accounted for below
    TEST_WRITER.waitForTraces(2)
    then:
    3 * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    (2.._) * TEST_CHECKPOINTER.checkpoint(_, THREAD_MIGRATION)
    (2.._) * TEST_CHECKPOINTER.checkpoint(_, THREAD_MIGRATION | END)
    (2.._) * TEST_CHECKPOINTER.checkpoint(_, CPU | END)
    3 * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.onRootSpan(_, _)
    0 * _
  }

  def "emit checkpoints with callback"() {
    when:
    runUnderTrace("parent") {
      executeRequest("GET", server.address, [:], {
        runUnderTrace("child") {}
      })
    }
    // note that the test http server is also traced and needs to be accounted for below
    TEST_WRITER.waitForTraces(2)
    then:
    4 * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    (2.._) * TEST_CHECKPOINTER.checkpoint(_, THREAD_MIGRATION)
    (2.._) * TEST_CHECKPOINTER.checkpoint(_, THREAD_MIGRATION | END)
    (2.._) * TEST_CHECKPOINTER.checkpoint(_, CPU | END)
    4 * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.onRootSpan(_, _)
    0 * _
  }

  def executeRequest(String method, URI uri, Map<String, String> headers, Closure callback = null) {
    def methodName = "prepare" + method.toLowerCase().capitalize()
    BoundRequestBuilder requestBuilder = asyncHttpClient."$methodName"(uri.toString())
    headers.each { requestBuilder.setHeader(it.key, it.value) }
    requestBuilder.setBody("hello")
    def response = requestBuilder.execute(new AsyncCompletionHandler() {
        @Override
        Object onCompleted(Response response) throws Exception {
          callback?.call()
          return response
        }
      }).get()
    blockUntilChildSpansFinished(1)
  }
}
