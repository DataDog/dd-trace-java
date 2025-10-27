import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.jaxrs2.JaxRsAnnotationsDecorator
import io.dropwizard.Application
import io.dropwizard.Configuration
import io.dropwizard.setup.Bootstrap
import io.dropwizard.setup.Environment
import io.dropwizard.testing.ConfigOverride
import io.dropwizard.testing.DropwizardTestSupport

import javax.ws.rs.GET
import javax.ws.rs.HeaderParam
import javax.ws.rs.Path
import javax.ws.rs.QueryParam
import javax.ws.rs.container.ContainerRequestContext
import javax.ws.rs.container.ContainerResponseContext
import javax.ws.rs.container.ContainerResponseFilter
import javax.ws.rs.core.Response
import javax.ws.rs.ext.Provider

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

import spock.lang.Ignore

@Ignore("Not working under Groovy 4")
class DropwizardTest extends HttpServerTest<DropwizardTestSupport> {

  class DropwizardServer implements HttpServer {
    def port = 0
    final DropwizardTestSupport testSupport

    DropwizardServer() {
      testSupport = new DropwizardTestSupport(testApp(),
        null,
        ConfigOverride.config("server.applicationConnectors[0].port", "0"))
    }

    @Override
    void start() {
      testSupport.before()
      port = testSupport.localPort
      assert port > 0
    }

    @Override
    void stop() {
      testSupport.after()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/")
    }

    @Override
    String toString() {
      return this.class.name
    }
  }

  @Override
  HttpServer server() {
    return new DropwizardServer()
  }

  Class testApp() {
    TestApp
  }

  Class testResource() {
    ServiceResource
  }

  @Override
  String component() {
    return "jax-rs"
  }

  @Override
  String expectedOperationName() {
    return "servlet.request"
  }

  @Override
  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    return "/${endpoint.relativeRawPath()}"
  }

  @Override
  String expectedIntegrationName() {
    "java-web-servlet"
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  @Override
  boolean testNotFound() {
    false
  }

  boolean testExceptionBody() {
    false
  }

  @Override
  boolean hasDecodedResource() {
    return false
  }

  @Override
  boolean hasExtraErrorInformation() {
    return true
  }

  @Override
  boolean changesAll404s() {
    true
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "jax-rs.request"
      resourceName "${testResource().simpleName}.${endpoint.name().toLowerCase()}"
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint == EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" JaxRsAnnotationsDecorator.DECORATE.component()
        if (endpoint == EXCEPTION) {
          errorTags(Exception, EXCEPTION.body)
        }
        defaultTags()
      }
    }
  }

  static class TestApp extends Application<Configuration> {
    @Override
    void initialize(Bootstrap<Configuration> bootstrap) {
    }

    @Override
    void run(Configuration configuration, Environment environment) {
      environment.jersey().register(ServiceResource)
      environment.jersey().register(ResponseHeaderFilter)
    }
  }

  @Path("/ignored1")
  static interface TestInterface {}

  @Path("/ignored2")
  static abstract class AbstractClass implements TestInterface {

    @GET
    @Path("success")
    Response success() {
      controller(SUCCESS) {
        Response.status(SUCCESS.status).entity(SUCCESS.body).build()
      }
    }

    @GET
    @Path("forwarded")
    Response forwarded(@HeaderParam("x-forwarded-for") String forwarded) {
      controller(FORWARDED) {
        Response.status(FORWARDED.status).entity(forwarded).build()
      }
    }

    @GET
    @Path("query")
    Response query_param(@QueryParam("some") String param) {
      controller(QUERY_PARAM) {
        Response.status(QUERY_PARAM.status).entity("some=$param".toString()).build()
      }
    }

    @GET
    @Path("encoded_query")
    Response query_encoded_query(@QueryParam("some") String param) {
      controller(QUERY_ENCODED_QUERY) {
        Response.status(QUERY_ENCODED_QUERY.status).entity("some=$param".toString()).build()
      }
    }

    @GET
    @Path("encoded%20path%20query")
    Response query_encoded_both(@QueryParam("some") String param) {
      controller(QUERY_ENCODED_BOTH) {
        Response.status(QUERY_ENCODED_BOTH.status).entity("some=$param".toString()).build()
      }
    }

    @GET
    @Path("redirect")
    Response redirect() {
      controller(REDIRECT) {
        Response.status(REDIRECT.status).location(new URI(REDIRECT.body)).build()
      }
    }
  }

  @Path("/ignored3")
  static class ParentClass extends AbstractClass {

    @GET
    @Path("error-status")
    Response error() {
      controller(ERROR) {
        Response.status(ERROR.status).entity(ERROR.body).build()
      }
    }

    @GET
    @Path("exception")
    Response exception() {
      controller(EXCEPTION) {
        throw new Exception(EXCEPTION.body)
      }
      return null
    }
  }

  @Path("/")
  static class ServiceResource extends ParentClass {}

  @Provider
  static class ResponseHeaderFilter implements ContainerResponseFilter {
    @Override
    void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
      responseContext.headers.add(IG_RESPONSE_HEADER, IG_RESPONSE_HEADER_VALUE)
    }
  }
}
