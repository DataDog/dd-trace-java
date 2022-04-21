import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.instrumentation.grizzlyhttp232.GrizzlyDecorator
import org.glassfish.grizzly.http.server.HttpServer
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory
import org.glassfish.jersey.server.ResourceConfig

import javax.ws.rs.Consumes
import javax.ws.rs.FormParam
import javax.ws.rs.GET
import javax.ws.rs.HeaderParam
import javax.ws.rs.NotFoundException
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.QueryParam
import javax.ws.rs.container.ContainerRequestContext
import javax.ws.rs.container.ContainerResponseContext
import javax.ws.rs.container.ContainerResponseFilter
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import javax.ws.rs.ext.ExceptionMapper
import javax.ws.rs.ext.Provider
import java.util.concurrent.TimeoutException

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class GrizzlyTest extends HttpServerTest<HttpServer> {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.grizzly-filterchain.enabled", "true")
  }

  class GrizzlyServer implements datadog.trace.agent.test.base.HttpServer {
    final HttpServer server
    int port = 0

    GrizzlyServer() {
      ResourceConfig rc = new ResourceConfig()
      rc.register(SimpleExceptionMapper)
      rc.register(resource())
      rc.register(ResponseServerFilter)
      rc.register(new TestMessageBodyReader())
      server = GrizzlyHttpServerFactory.createHttpServer(new URI("http://localhost:0"), rc, false)
    }

    @Override
    void start() throws TimeoutException {
      server.start()
      port = server.getListener("grizzly").port
    }

    @Override
    void stop() {
      server.shutdownNow()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/")
    }
  }

  @Override
  datadog.trace.agent.test.base.HttpServer server() {
    return new GrizzlyServer()
  }

  Class<ServiceResource> resource() {
    return ServiceResource
  }

  @Override
  String component() {
    return GrizzlyDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return GrizzlyDecorator.GRIZZLY_REQUEST.toString()
  }

  @Override
  String expectedResourceName(ServerEndpoint endpoint, String method, URI address) {
    if (endpoint == PATH_PARAM) {
      'GET /path/?/param'
    } else {
      super.expectedResourceName(endpoint, method, address)
    }
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testBodyJson() {
    true
  }

  @Override
  String testPathParam() {
    true
  }

  @Override
  Map<String, ?> expectedIGPathParams() {
    [id: '123']
  }

  static class SimpleExceptionMapper implements ExceptionMapper<Throwable> {

    @Override
    Response toResponse(Throwable exception) {
      if (exception instanceof NotFoundException) {
        return exception.getResponse()
      }
      Response.status(500).entity(exception.message).build()
    }
  }

  @Path("/")
  static class ServiceResource {

    @GET
    @Path("success")
    Response success() {
      controller(SUCCESS) {
        Response.status(SUCCESS.status).entity(SUCCESS.body).build()
      }
    }

    @POST
    @Path('created')
    Response created(String reqBody) {
      controller(CREATED) {
        String body = "${CREATED.body}: ${reqBody}"
        Response.status(CREATED.status).entity(body).build()
      }
    }

    @GET
    @Path("forwarded")
    Response forwarded(@HeaderParam("x-forwarded-for") String forwarded) {
      controller(FORWARDED) {
        Response.status(FORWARDED.status).entity(forwarded).build()
      }
    }

    @POST
    @Path("body-urlencoded")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    Response bodyUrlencoded(@FormParam("a") List<String> a) {
      controller(BODY_URLENCODED) {
        Response.status(BODY_URLENCODED.status).entity([a: a] as String).build()
      }
    }

    @POST
    @Path("body-json")
    Response bodyJson(ClassToConvertBodyTo obj) {
      controller(BODY_JSON) {
        Response.status(BODY_JSON.status).entity("""{"a":"${obj.a}"}""" as String).build()
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
    @Path('path/{id: \\d+}/param')
    String param(@PathParam('id') int id) {
      controller(PATH_PARAM) {
        id as String
      }
    }

    @GET
    @Path("redirect")
    Response redirect() {
      controller(REDIRECT) {
        Response.status(REDIRECT.status).location(new URI(REDIRECT.body)).build()
      }
    }

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

  @Provider
  static class ResponseServerFilter implements ContainerResponseFilter {
    @Override
    void filter(ContainerRequestContext requestContext,
      ContainerResponseContext responseContext) throws IOException {
      responseContext.getHeaders().add(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE)
    }
  }
}
