import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.jaxrs.JaxRsClientDecorator
import org.apache.cxf.jaxrs.client.spec.ClientBuilderImpl
import org.glassfish.jersey.client.ClientConfig
import org.glassfish.jersey.client.ClientProperties
import org.glassfish.jersey.client.JerseyClientBuilder
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder
import spock.lang.Timeout

import javax.ws.rs.client.Client
import javax.ws.rs.client.ClientBuilder
import javax.ws.rs.client.Entity
import javax.ws.rs.client.Invocation
import javax.ws.rs.client.WebTarget
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import java.util.concurrent.TimeUnit

abstract class JaxRsClientTest extends HttpClientTest {

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def isProxy = uri.fragment != null && uri.fragment.equals("proxy")
    def clientBuilder = builder(isProxy)
    if (uri.scheme.equals("https")) {
      clientBuilder.sslContext(server.sslContext)
    }
    Client client = clientBuilder.build()
    WebTarget service = client.target(uri)
    Invocation.Builder request = service.request(MediaType.TEXT_PLAIN)
    headers.each { request.header(it.key, it.value) }
    def reqBody = BODY_METHODS.contains(method) ? Entity.text(body) : null
    Response response = request.method(method, (Entity) reqBody)
    callback?.call()

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
class JerseyClientTest extends JaxRsClientTest {

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
class ResteasyClientTest extends JaxRsClientTest {

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
class CxfClientTest extends JaxRsClientTest {

  @Override
  ClientBuilder builder(boolean useProxy) {
    return new ClientBuilderImpl()
    //      .property(ClientImpl.HTTP_CONNECTION_TIMEOUT_PROP, (long) CONNECT_TIMEOUT_MS)
    //      .property(ClientImpl.HTTP_RECEIVE_TIMEOUT_PROP, (long) READ_TIMEOUT_MS)
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
