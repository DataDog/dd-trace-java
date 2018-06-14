import io.opentracing.tag.Tags
import org.apache.cxf.jaxrs.client.spec.ClientBuilderImpl
import org.glassfish.jersey.client.JerseyClientBuilder
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder
import ratpack.http.Headers
import spock.lang.Unroll
import stackstate.trace.agent.test.AgentTestRunner
import stackstate.trace.api.STSSpanTypes
import stackstate.trace.api.STSTags

import javax.ws.rs.client.AsyncInvoker
import javax.ws.rs.client.Client
import javax.ws.rs.client.Invocation
import javax.ws.rs.client.WebTarget
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import java.util.concurrent.atomic.AtomicReference

import static ratpack.groovy.test.embed.GroovyEmbeddedApp.ratpack

//@Timeout(10)
class JaxRsClientTest extends AgentTestRunner {
  static {
    System.setProperty("sts.integration.jax-rs.enabled", "true")
  }

  def receivedHeaders = new AtomicReference<Headers>()
  def server = ratpack {
    handlers {
      all {
        receivedHeaders.set(request.headers)
        response.status(200).send("pong")
      }
    }
  }

  @Unroll
  def "#lib request creates spans and sends headers"() {
    setup:
    Client client = builder.build()
    WebTarget service = client.target("http://localhost:$server.address.port/ping")
    Response response
    if (async) {
      AsyncInvoker request = service.request(MediaType.TEXT_PLAIN).async()
      response = request.get().get()
    } else {
      Invocation.Builder request = service.request(MediaType.TEXT_PLAIN)
      response = request.get()
    }

    expect:
    response.readEntity(String) == "pong"

    TEST_WRITER.size() == 1

    def trace = TEST_WRITER.firstTrace()
    trace.size() == 1

    and:
    def span = trace[0]

    span.context().operationName == "jax-rs.client.call"
    span.serviceName == "unnamed-java-app"
    span.resourceName == "GET jax-rs.client.call"
    span.type == "http"
    !span.context().getErrorFlag()
    span.context().parentId == 0


    def tags = span.context().tags
    tags[Tags.COMPONENT.key] == "jax-rs.client"
    tags[Tags.SPAN_KIND.key] == Tags.SPAN_KIND_CLIENT
    tags[Tags.HTTP_METHOD.key] == "GET"
    tags[Tags.HTTP_STATUS.key] == 200
    tags[Tags.HTTP_URL.key] == "http://localhost:$server.address.port/ping"
    tags[STSTags.SPAN_TYPE] == STSSpanTypes.HTTP_CLIENT
    tags[STSTags.THREAD_NAME] != null
    tags[STSTags.THREAD_ID] != null
    tags[STSTags.SPAN_PID] != 0
    tags[STSTags.SPAN_HOSTNAME] != ""
    tags.size() == 10

    receivedHeaders.get().get("x-stackstate-trace-id") == "$span.traceId"
    receivedHeaders.get().get("x-stackstate-parent-id") == "$span.spanId"

    cleanup:
    server.close()

    where:
    builder                     | async | lib
    new JerseyClientBuilder()   | false | "jersey"
    new ClientBuilderImpl()     | false | "cxf"
    new ResteasyClientBuilder() | false | "resteasy"
    new JerseyClientBuilder()   | true  | "jersey async"
    new ClientBuilderImpl()     | true  | "cxf async"
    new ResteasyClientBuilder() | true  | "resteasy async"
  }
}
