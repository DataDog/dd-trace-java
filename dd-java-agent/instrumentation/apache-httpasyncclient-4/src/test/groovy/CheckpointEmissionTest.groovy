import datadog.trace.agent.test.AgentTestRunner
import org.apache.http.HttpResponse
import org.apache.http.client.config.RequestConfig
import org.apache.http.concurrent.FutureCallback
import org.apache.http.impl.nio.client.HttpAsyncClients
import org.apache.http.message.BasicHeader
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.CountDownLatch

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class CheckpointEmissionTest extends AgentTestRunner {

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

  @Shared
  RequestConfig requestConfig = RequestConfig.custom()
  .setConnectTimeout(1000)
  .setSocketTimeout(1000)
  .build()

  @AutoCleanup
  @Shared
  def client = HttpAsyncClients.custom()
  .setDefaultRequestConfig(requestConfig)
  .build()

  def setupSpec() {
    client.start()
  }


  def executeRequest(String method, URI uri, Map<String, String> headers, Closure callback = null) {
    def request = new HttpUriRequest(method, uri)
    headers.entrySet().each {
      request.addHeader(new BasicHeader(it.key, it.value))
    }

    def latch = new CountDownLatch(callback == null ? 0 : 1)

    def handler = callback == null ? null : new FutureCallback<HttpResponse>() {

        @Override
        void completed(HttpResponse result) {
          callback()
          latch.countDown()
        }

        @Override
        void failed(Exception ex) {
          latch.countDown()
        }

        @Override
        void cancelled() {
          latch.countDown()
        }
      }

    try {
      def response = client.execute(request, handler).get()
      response.entity?.content?.close()
      latch.await()
      response.statusLine.statusCode
    } finally {
      blockUntilChildSpansFinished(1)
    }
  }
}
