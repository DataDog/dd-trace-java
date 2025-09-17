package datadog.trace.instrumentation.netty38

import com.ning.http.client.AsyncCompletionHandler
import com.ning.http.client.AsyncHttpClient
import com.ning.http.client.AsyncHttpClientConfig
import com.ning.http.client.Response
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingNettyHttpNamingConventions
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.netty38.client.NettyHttpClientDecorator
import org.jboss.netty.handler.codec.embedder.EncoderEmbedder
import org.jboss.netty.handler.codec.http.DefaultHttpRequest
import org.jboss.netty.handler.codec.http.HttpMethod
import org.jboss.netty.handler.codec.http.HttpRequestEncoder
import org.jboss.netty.handler.codec.http.HttpVersion
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.PortUtils.UNUSABLE_PORT
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

abstract class Netty38ClientTest extends HttpClientTest {

  @Shared
  def clientConfig = new AsyncHttpClientConfig.Builder()
  .setRequestTimeoutInMs(TimeUnit.SECONDS.toMillis(10).toInteger())
  .setSSLContext(server.sslContext)
  .build()

  @Shared
  @AutoCleanup
  AsyncHttpClient asyncHttpClient = new AsyncHttpClient(clientConfig)

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def methodName = "prepare" + method.toLowerCase().capitalize()
    def requestBuilder = asyncHttpClient."$methodName"(uri.toString())
    headers.each { requestBuilder.setHeader(it.key, it.value) }
    def response = requestBuilder.execute(new AsyncCompletionHandler() {
        @Override
        Object onCompleted(Response response) throws Exception {
          callback?.call()
          return response
        }
      }).get()
    blockUntilChildSpansFinished(1)
    return response.statusCode
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
  boolean testSecure() {
    true
  }

  @Override
  boolean testRemoteConnection() {
    return false
  }

  def "connection error (unopened port)"() {
    given:
    def uri = new URI("http://localhost:$UNUSABLE_PORT/")

    when:
    runUnderTrace("parent") {
      doRequest(method, uri)
    }

    then:
    def ex = thrown(Exception)
    def thrownException = ex instanceof ExecutionException ? ex.cause : ex

    and:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent", null, thrownException)

        span {
          operationName "netty.connect"
          resourceName "netty.connect"
          childOf span(0)
          errored true
          tags {
            "$Tags.COMPONENT" "netty"
            Class errorClass = ConnectException
            try {
              errorClass = Class.forName('io.netty.channel.AbstractChannel$AnnotatedConnectException')
            } catch (ClassNotFoundException e) {
              // Older versions use 'java.net.ConnectException' and do not have 'io.netty.channel.AbstractChannel$AnnotatedConnectException'
            }
            errorTags errorClass, "Connection refused: localhost/127.0.0.1:$UNUSABLE_PORT"
            defaultTags()
          }
        }
      }
    }

    where:
    method = "GET"
  }

  def "verify instrumentation does not break embedded channels"() {
    given:
    EncoderEmbedder encoderEmbedder = new EncoderEmbedder<>(new HttpRequestEncoder())

    when:
    encoderEmbedder.offer(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/post"))

    then:
    noExceptionThrown()
  }
}

class Netty38ClientV0ForkedTest extends Netty38ClientTest implements TestingNettyHttpNamingConventions.ClientV0  {
}

class Netty38ClientV1ForkedTest extends Netty38ClientTest implements TestingNettyHttpNamingConventions.ClientV1 {
}
