package smoketest;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.Map;

@Path("/hello")
public class Resource {

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String hello() {
    return "Jersey hello world example.";
  }

  @Path("/bypathparam/{name}")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String byPathParam(@PathParam("name") String name) {
    return "Jersey: hello " + name;
  }

  @Path("/byqueryparam")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String byQueryParam(@QueryParam("param") String param) {
    return "Jersey: hello " + param;
  }

  @Path("/byheader")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String byHeader(@HeaderParam("X-Custom-header") String param) {
    return "Jersey: hello " + param;
  }

  @Path("/bycookie")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String byCookie(@CookieParam("cookieName") String param) {
    return "Jersey: hello " + param;
  }

  @GET
  @Path("/cookiename")
  public String sourceCookieName(@Context final HttpHeaders headers) {
    Map<String, Cookie> cookies = headers.getCookies();
    for (Cookie cookie : cookies.values()) {
      if (cookie.getName().equalsIgnoreCase("cookieName")) {
        String cookieName = cookie.getName();
        return "Jersey: hello " + cookieName;
      }
    }
    return "cookie not found";
  }

  @GET
  @Path("/headername")
  public String sourceHeaderName(@Context final HttpHeaders headers) {
    for (String headerName : headers.getRequestHeaders().keySet()) {
      if (headerName.equalsIgnoreCase("X-Custom-header")) {
        return "Jersey: hello " + headerName;
      }
    }
    return "header not found";
  }

  @GET
  @Path("/cookieobjectvalue")
  public String sourceCookieValue(@Context final HttpHeaders headers) {
    Map<String, Cookie> cookies = headers.getCookies();
    for (Cookie cookie : cookies.values()) {
      if (cookie.getName().equalsIgnoreCase("cookieName")) {
        String cookieValue = cookie.getValue();
        return "Jersey: hello " + cookieValue;
      }
    }
    return "cookie not found";
  }

  @POST
  @Path("/formparameter")
  public String sourceParameterName(@FormParam("formParam1Name") final String formParam1Value) {
    return String.format("Jersey: hello " + formParam1Value);
  }

  @POST
  @Path("/formparametername")
  public String sourceParameterName(Form form) {
    for (String paramName : form.asMap().keySet()) {
      if (paramName.equalsIgnoreCase("formParam1Name")) {
        return "Jersey: hello " + paramName;
      }
    }
    return "Parameter name not found";
  }

  @Path("/setlocationheader")
  @GET
  public Response locationHeader(@QueryParam("param") String param) {
    return Response.status(Response.Status.TEMPORARY_REDIRECT).header("Location", param).build();
  }

  @Path("/setresponselocation")
  @GET
  public Response responseLocation(@QueryParam("param") String param) throws URISyntaxException {
    return Response.status(Response.Status.TEMPORARY_REDIRECT).location(new URI(param)).build();
  }

  @Path("/insecurecookie")
  @GET
  public Response getCookie() throws SQLException {
    return Response.ok().cookie(new NewCookie("user-id", "7")).build();
  }

  @Path("/api_security/response")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response bodyJson() {
    TestEntity testEntity = new TestEntity("testing", "test");
    return Response.ok().entity(testEntity).build();
  }

  @GET
  @Path("/api_security/sampling/{i}")
  public Response apiSecuritySamplingWithStatus(@PathParam("i") int i) {
    return Response.status(i).header("content-type", "text/plain").entity("Hello!\n").build();
  }
}
