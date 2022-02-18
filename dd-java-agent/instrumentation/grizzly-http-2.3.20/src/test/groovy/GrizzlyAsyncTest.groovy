import javax.ws.rs.Consumes
import javax.ws.rs.FormParam
import javax.ws.rs.GET
import javax.ws.rs.HeaderParam
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.QueryParam
import javax.ws.rs.container.AsyncResponse
import javax.ws.rs.container.Suspended
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class GrizzlyAsyncTest extends GrizzlyTest {

  @Override
  Class<AsyncServiceResource> resource() {
    return AsyncServiceResource
  }

  @Path("/")
  static class AsyncServiceResource {
    private ExecutorService executor = Executors.newSingleThreadExecutor()

    @GET
    @Path("success")
    void success(@Suspended AsyncResponse ar) {
      executor.execute {
        controller(SUCCESS) {
          ar.resume(Response.status(SUCCESS.status).entity(SUCCESS.body).build())
        }
      }
    }

    @POST
    @Path('created')
    Response created(@Suspended AsyncResponse ar, String reqBody) {
      executor.execute {
        controller(CREATED) {
          String body = "${CREATED.body}: ${reqBody}"
          ar.resume(Response.status(CREATED.status).entity(body).build())
        }
      }
    }

    @GET
    @Path("forwarded")
    Response forwarded(@Suspended final AsyncResponse asyncResponse, @HeaderParam("x-forwarded-for") String forwarded) {
      executor.execute {
        controller(FORWARDED) {
          asyncResponse.resume(Response.status(FORWARDED.status).entity(forwarded).build())
        }
      }
    }

    @POST
    @Path("body-urlencoded")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    Response bodyUrlencoded(@Suspended final AsyncResponse asyncResponse, @FormParam("a") String a) {
      executor.execute {
        controller(BODY_URLENCODED) {
          asyncResponse.resume(Response.status(BODY_URLENCODED.status).entity([a: [a]] as String).build())
        }
      }
    }

    @POST
    @Path("body-json")
    Response bodyJson(@Suspended final AsyncResponse asyncResponse, ClassToConvertBodyTo obj) {
      executor.execute {
        controller(BODY_JSON) {
          asyncResponse.resume(
            Response.status(BODY_JSON.status).entity("""{"a":"${obj.a}"}""" as String).build())
        }
      }
    }

    @GET
    @Path("query")
    Response query_param(@QueryParam("some") String param, @Suspended AsyncResponse ar) {
      controller(QUERY_PARAM) {
        ar.resume(Response.status(QUERY_PARAM.status).entity("some=$param".toString()).build())
      }
    }

    @GET
    @Path("encoded_query")
    Response query_encoded_query(@QueryParam("some") String param, @Suspended AsyncResponse ar) {
      controller(QUERY_ENCODED_QUERY) {
        ar.resume(Response.status(QUERY_ENCODED_QUERY.status).entity("some=$param".toString()).build())
      }
    }

    @GET
    @Path("encoded%20path%20query")
    Response query_encoded_both(@QueryParam("some") String param, @Suspended AsyncResponse ar) {
      controller(QUERY_ENCODED_BOTH) {
        ar.resume(Response.status(QUERY_ENCODED_BOTH.status).entity("some=$param".toString()).build())
      }
    }

    @GET
    @Path("redirect")
    void redirect(@Suspended AsyncResponse ar) {
      executor.execute {
        controller(REDIRECT) {
          ar.resume(Response.status(REDIRECT.status).location(new URI(REDIRECT.body)).build())
        }
      }
    }

    @GET
    @Path("error-status")
    void error(@Suspended AsyncResponse ar) {
      executor.execute {
        controller(ERROR) {
          ar.resume(Response.status(ERROR.status).entity(ERROR.body).build())
        }
      }
    }

    @GET
    @Path("exception")
    void exception(@Suspended AsyncResponse ar) {
      executor.execute {
        try {
          controller(EXCEPTION) {
            throw new Exception(EXCEPTION.body)
          }
        } catch (Exception e) {
          ar.resume(e)
        }
      }
    }
  }
}
