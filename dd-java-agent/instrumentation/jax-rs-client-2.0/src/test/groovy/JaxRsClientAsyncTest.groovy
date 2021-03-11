import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.jaxrs.JaxRsClientDecorator
import org.apache.cxf.jaxrs.client.spec.ClientBuilderImpl
import org.glassfish.jersey.client.ClientConfig
import org.glassfish.jersey.client.ClientProperties
import org.glassfish.jersey.client.JerseyClientBuilder
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder
import spock.lang.Timeout

import javax.ws.rs.client.AsyncInvoker
import javax.ws.rs.client.Client
import javax.ws.rs.client.ClientBuilder
import javax.ws.rs.client.Entity
import javax.ws.rs.client.InvocationCallback
import javax.ws.rs.client.WebTarget
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class JaxRsClientAsyncTest extends HttpClientTest {

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def isProxy = uri.fragment != null && uri.fragment.equals("proxy")
    def clientBuilder = builder(isProxy)
    if (uri.scheme.equals("https")) {
      clientBuilder.sslContext(server.sslContext)
    }
    Client client = clientBuilder.build()
    WebTarget service = client.target(uri)
    def builder = service.request(MediaType.TEXT_PLAIN)
    headers.each { builder.header(it.key, it.value) }
    AsyncInvoker request = builder.async()

    def latch = new CountDownLatch(1)
    def reqBody = BODY_METHODS.contains(method) ? Entity.text(body) : null
    Response response = request.method(method, (Entity) reqBody, new InvocationCallback<Response>() {
        @Override
        void completed(Response s) {
          callback?.call()
          latch.countDown()
        }

        @Override
        void failed(Throwable throwable) {
        }
      }).get()

    latch.await()
    return response.status
  }

  @Override
  CharSequence component() {
    return JaxRsClientDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "jax-rs.client.call"
  }

  abstract ClientBuilder builder(boolean useProxy)
}

@Timeout(5)
class JerseyClientAsyncTest extends JaxRsClientAsyncTest {

  @Override
  ClientBuilder builder(boolean useProxy) {
    ClientConfig config = new ClientConfig()
    config.property(ClientProperties.CONNECT_TIMEOUT, CONNECT_TIMEOUT_MS)
    config.property(ClientProperties.READ_TIMEOUT, READ_TIMEOUT_MS)
    return new JerseyClientBuilder().withConfig(config)
  }

  boolean testCircularRedirects() {
    false
  }

  @Override
  boolean testProxy() {
    // uses HttpUrlConnection under the hood and not easy to configure proxy settings
    return false
  }
}

@Timeout(5)
class ResteasyClientAsyncTest extends JaxRsClientAsyncTest {

  @Override
  ClientBuilder builder(boolean useProxy) {
    def builder = new ResteasyClientBuilder()
      .establishConnectionTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .socketTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    return useProxy ? builder.defaultProxy("localhost", proxy.port) : builder
  }

  boolean testRedirects() {
    false
  }
}

@Timeout(5)
class CxfClientAsyncTest extends JaxRsClientAsyncTest {

  @Override
  ClientBuilder builder(boolean useProxy) {
    return new ClientBuilderImpl()
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
    // FIXME: span not reported correctly.
    false
  }

  @Override
  boolean testProxy() {
    return false
  }
}
