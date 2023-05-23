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
  public String byPathParam(@PathParam("name") String name) throws SQLException {
    DB.store(name);
    return "Jersey: hello " + name;
  }

  @Path("/byqueryparam")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String byQueryParam(@QueryParam("param") String param) throws SQLException {
    DB.store(param);
    return "Jersey: hello " + param;
  }

  @Path("/byheader")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String byHeader(@HeaderParam("X-Custom-header") String param) throws SQLException {
    DB.store(param);
    return "Jersey: hello " + param;
  }

  @Path("/bycookie")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String byCookie(@CookieParam("cookieName") String param) throws SQLException {
    DB.store(param);
    return "Jersey: hello " + param;
  }

  @GET
  @Path("/cookiename")
  public String sourceCookieName(@Context final HttpHeaders headers) throws SQLException {
    Map<String, Cookie> cookies = headers.getCookies();
    for (Cookie cookie : cookies.values()) {
      String cookieName = cookie.getName();
      DB.store(cookieName);
      return "Jersey: hello " + cookieName;
    }
    return "cookie not found";
  }

  @GET
  @Path("/headername")
  public String sourceHeaderName(@Context final HttpHeaders headers) throws SQLException {
    for (String headerName : headers.getRequestHeaders().keySet()) {
      DB.store(headerName);
      return "Jersey: hello " + headerName;
    }
    return "cookie not found";
  }

  @GET
  @Path("/cookieobjectvalue")
  public String sourceCookieValue(@Context final HttpHeaders headers) throws SQLException {
    Map<String, Cookie> cookies = headers.getCookies();
    for (Cookie cookie : cookies.values()) {
      String cookieValue = cookie.getValue();
      DB.store(cookieValue);
      return "Jersey: hello " + cookieValue;
    }
    return "cookie not found";
  }

  @POST
  @Path("/formparameter")
  public String sourceParameterName(@FormParam("formParam1Name") final String formParam1Value)
      throws SQLException {
    DB.store(formParam1Value);
    return String.format("Jersey: hello " + formParam1Value);
  }

  @POST
  @Path("/formparametername")
  public String sourceParameterName(Form form) throws SQLException {
    for (String paramName : form.asMap().keySet()) {
      DB.store(paramName);
      return "Jersey: hello " + paramName;
    }
    return String.format("Parameter name not found");
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
}
