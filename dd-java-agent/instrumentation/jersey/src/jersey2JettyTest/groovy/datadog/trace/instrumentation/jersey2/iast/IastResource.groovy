package datadog.trace.instrumentation.jersey2.iast

import javax.ws.rs.CookieParam
import javax.ws.rs.FormParam
import javax.ws.rs.GET
import javax.ws.rs.HeaderParam
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.Context
import javax.ws.rs.core.Form
import javax.ws.rs.core.HttpHeaders
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.UriInfo

import static com.datadog.iast.test.TaintMarkerHelpers.t

@Path("/iast")
class IastResource {

  @Path("/path/{name}")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  String path(@PathParam("name") String value) {
    return "IAST: ${t(value)}"
  }

  @Path("/all_path/{name}")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  String allPath(@Context UriInfo uri) {
    def pairList = collectMultiMap(uri.pathParameters)
    return "IAST: ${t(pairList)}"
  }

  @Path("/query")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  String query(@QueryParam("var") String value) {
    return "IAST: ${t(value)}"
  }

  @Path("/all_query")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  String allQuery(@Context UriInfo uri) {
    def pairList = collectMultiMap(uri.queryParameters)
    return "IAST: ${t(pairList)}"
  }

  @Path("/header")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  String header(@HeaderParam("x-my-header") String value) {
    return "IAST: ${t(value)}"
  }

  @Path("/all_headers")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  String allHeaders(@Context final HttpHeaders headers) {
    def pairList = collectMultiMap(headers.requestHeaders)
    return "IAST: ${pairList}"
  }

  @Path("/cookie")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  String cookie(@CookieParam("var1") String value) {
    return "IAST: ${t(value)}"
  }

  @Path("/all_cookies")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  String allCookies(@Context final HttpHeaders headers) {
    def pairList = headers.cookies.values().collectEntries { cookie ->
      [(t(cookie.name)): t(cookie.value)]
    }
    return "IAST: ${pairList}"
  }

  @Path("/form")
  @POST
  @Produces(MediaType.TEXT_PLAIN)
  String form(@FormParam("var") String value) {
    return "IAST: ${t(value)}"
  }

  @Path("/all_form")
  @POST
  @Produces(MediaType.TEXT_PLAIN)
  String form(final Form form) {
    def pairList = collectMultiMap(form.asMap())
    return "IAST: ${pairList}"
  }

  @Path("/all_form_map")
  @POST
  @Produces(MediaType.TEXT_PLAIN)
  String form(final MultivaluedMap<String, String> form) {
    def pairList = collectMultiMap(form)
    return "IAST: ${pairList}"
  }

  private static collectMultiMap(final MultivaluedMap<String, String> map) {
    return map.keySet().sort().collect {key ->
      final values = map[key]
      return [(t(key)): values.collect { t(it) }]
    }
  }
}
