import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.jaxrs.JaxRsClientDecorator
import datadog.trace.test.util.Flaky
import org.apache.cxf.jaxrs.client.spec.ClientBuilderImpl
import org.glassfish.jersey.client.ClientConfig
import org.glassfish.jersey.client.ClientProperties
import org.glassfish.jersey.client.JerseyClientBuilder
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder
import org.spockframework.runtime.ConditionNotSatisfiedError
import spock.lang.IgnoreIf
import spock.lang.Retry
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

    Client client = builder().build()
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

@Flaky
@Retry(exceptions = [ConditionNotSatisfiedError])
@Timeout(5)
class JerseyClientTest extends JaxRsClientTest {

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

abstract class ResteasyClientTest extends JaxRsClientTest {

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
class ResteasyClientV0ForkedTest extends ResteasyClientTest {
}

@Timeout(5)
class ResteasyClientV1ForkedTest extends ResteasyClientTest implements TestingGenericHttpNamingConventions.ClientV1 {
}

@Timeout(5)
@IgnoreIf({
  // TODO Java 17: This version of apache-cxf doesn't work on Java 17
  //  exception in org.apache.cxf.common.util.ReflectionUtil
  JavaVirtualMachine.isJavaVersionAtLeast(17)
})
class CxfClientTest extends JaxRsClientTest {

  @Override
  ClientBuilder builder() {
    return new ClientBuilderImpl()
    //      .property(ClientImpl.HTTP_CONNECTION_TIMEOUT_PROP, (long) CONNECT_TIMEOUT_MS)
    //      .property(ClientImpl.HTTP_RECEIVE_TIMEOUT_PROP, (long) READ_TIMEOUT_MS)
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
}
