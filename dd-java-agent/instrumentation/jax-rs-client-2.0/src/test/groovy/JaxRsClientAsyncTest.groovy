import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.jaxrs.JaxRsClientDecorator
import org.apache.cxf.jaxrs.client.spec.ClientBuilderImpl
import org.glassfish.jersey.client.ClientConfig
import org.glassfish.jersey.client.ClientProperties
import org.glassfish.jersey.client.JerseyClientBuilder
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder
import spock.lang.IgnoreIf
import spock.lang.Timeout

import javax.ws.rs.client.*
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class JaxRsClientAsyncTest extends HttpClientTest  {
  @Override
  def setup() {
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    Client client = builder().build()
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
    def status = response.status
    // Sometimes tests fail with one extra span, that is a duplicate of the server
    // span, and there seems to be an issue with intermittent _double_ requests, and
    // closing the response is a fix according to this:
    // https://github.com/folkol/intermittent-duplicate-requests-from-jersey-client
    response.close()
    return status
  }

  @Override
  CharSequence component() {
    return JaxRsClientDecorator.DECORATE.component()
  }



  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return "jax-rs.client.call"
  }

  abstract ClientBuilder builder()
}

@Timeout(5)
class JerseyClientAsyncTest extends JaxRsClientAsyncTest {

  @Override
  ClientBuilder builder() {
    ClientConfig config = new ClientConfig()
    config.property(ClientProperties.CONNECT_TIMEOUT, CONNECT_TIMEOUT_MS)
    config.property(ClientProperties.READ_TIMEOUT, READ_TIMEOUT_MS)
    return new JerseyClientBuilder().withConfig(config)
  }

  boolean testCircularRedirects() {
    false
  }
}

abstract class ResteasyClientAsyncTest extends JaxRsClientAsyncTest {

  @Override
  ClientBuilder builder() {
    return new ResteasyClientBuilder()
      .establishConnectionTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .socketTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  }

  boolean testRedirects() {
    false
  }
}
@Timeout(5)
class ResteasyClientAsyncV0ForkedTest extends ResteasyClientAsyncTest {
}

@Timeout(5)
class ResteasyClientAsyncV1ForkedTest extends ResteasyClientAsyncTest implements TestingGenericHttpNamingConventions.ClientV1{
}

@Timeout(5)
@IgnoreIf({
  // TODO Java 17: This version of apache-cxf doesn't work on Java 17
  //  exception in org.apache.cxf.common.util.ReflectionUtil
  JavaVirtualMachine.isJavaVersionAtLeast(17)
})
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

  boolean testRemoteConnection() {
    // FIXME: span not reported correctly.
    false
  }

  @Override
  def setup() {
  }
}
