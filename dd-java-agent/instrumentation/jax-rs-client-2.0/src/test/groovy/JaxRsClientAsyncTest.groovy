import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.jaxrs.JaxRsClientDecorator
import org.apache.cxf.jaxrs.client.spec.ClientBuilderImpl
import org.glassfish.jersey.client.JerseyClientBuilder
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder

import javax.ws.rs.client.AsyncInvoker
import javax.ws.rs.client.Client
import javax.ws.rs.client.ClientBuilder
import javax.ws.rs.client.Entity
import javax.ws.rs.client.InvocationCallback
import javax.ws.rs.client.WebTarget
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

abstract class JaxRsClientAsyncTest extends HttpClientTest<JaxRsClientDecorator> {

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, Closure callback) {
    Client client = builder().build()
    WebTarget service = client.target(uri)
    def builder = service.request(MediaType.TEXT_PLAIN)
    headers.each { builder.header(it.key, it.value) }
    AsyncInvoker request = builder.async()

    def body = BODY_METHODS.contains(method) ? Entity.text("") : null
    Response response = request.method(method, (Entity) body, new InvocationCallback<Response>() {
      @Override
      void completed(Response s) {
        callback?.call()
      }

      @Override
      void failed(Throwable throwable) {
      }
    }).get()

    return response.status
  }

  @Override
  JaxRsClientDecorator decorator() {
    return JaxRsClientDecorator.DECORATE
  }

  @Override
  String expectedOperationName() {
    return "jax-rs.client.call"
  }

  abstract ClientBuilder builder()
}

class JerseyClientAsyncTest extends JaxRsClientAsyncTest {

  @Override
  ClientBuilder builder() {
    return new JerseyClientBuilder()
  }

  boolean testCircularRedirects() {
    false
  }
}

class ResteasyClientAsyncTest extends JaxRsClientAsyncTest {

  @Override
  ClientBuilder builder() {
    return new ResteasyClientBuilder()
  }

  boolean testRedirects() {
    false
  }
}

class CxfClientAsyncTest extends JaxRsClientAsyncTest {

  @Override
  ClientBuilder builder() {
    return new ClientBuilderImpl()
  }

  boolean testRedirects() {
    false
  }

  boolean testConnectionFailure() {
    false
  }
}
