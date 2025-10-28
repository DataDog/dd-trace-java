import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import org.glassfish.grizzly.http.server.HttpServer as GrizzlyHttpServer
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory
import org.glassfish.jersey.media.multipart.MultiPartFeature
import org.glassfish.jersey.server.ResourceConfig

import javax.ws.rs.NotFoundException
import javax.ws.rs.container.ContainerRequestContext
import javax.ws.rs.container.ContainerResponseContext
import javax.ws.rs.container.ContainerResponseFilter
import javax.ws.rs.core.Response
import javax.ws.rs.ext.ExceptionMapper
import javax.ws.rs.ext.Provider

class GrizzlyServer implements HttpServer {
  final GrizzlyHttpServer server
  int port = 0

  GrizzlyServer(Class resource) {
    ResourceConfig rc = new ResourceConfig()
    rc.register(SimpleExceptionMapper)
    rc.register(resource)
    rc.register(MultiPartFeature)
    rc.register(ResponseServerFilter)
    rc.register(new TestMessageBodyReader())
    server = GrizzlyHttpServerFactory.createHttpServer(new URI("http://localhost:0"), rc, false)
  }

  @Override
  void start() {
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

  @Provider
  static class ResponseServerFilter implements ContainerResponseFilter {
    @Override
    void filter(ContainerRequestContext requestContext,
      ContainerResponseContext responseContext) throws IOException {
      responseContext.getHeaders().add(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE)
    }
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
}
