package datadog.trace.instrumentation.jersey3

import datadog.appsec.api.blocking.Blocking
import jakarta.ws.rs.Produces
import org.glassfish.jersey.media.multipart.FormDataParam

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.USER_BLOCK
import static datadog.trace.agent.test.base.HttpServerTest.controller


@Path("/")
class ServiceResource {

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
  @Path("/path/{id}/param")
  Response pathParam(@PathParam("id") String id) {
    controller(PATH_PARAM) {
      Response.status(PATH_PARAM.status).entity(id).build()
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
  @Path("body-multipart")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  Response bodyMultipart(@FormDataParam("a") List<String> a) {
    controller(BODY_MULTIPART) {
      Response.status(BODY_MULTIPART.status).entity([a: a] as String).build()
    }
  }

  @POST
  @Path("body-json")
  @Produces(MediaType.APPLICATION_JSON)
  Response bodyJson(ClassToConvertBodyTo obj) {
    controller(BODY_JSON, () ->
    Response.status(BODY_JSON.status)
    .entity(obj)
    .build()
    )
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

  @GET
  @Path("error-status")
  Response error() {
    controller(ERROR) {
      Response.status(ERROR.status).entity(ERROR.body).build()
    }
  }

  @GET
  @Path("user-block")
  Response userBlock() {
    controller(USER_BLOCK) {
      Blocking.forUser('user-to-block').blockIfMatch()
      Response.status(200).entity('should not be reached').build()
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
