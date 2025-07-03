package smoketest.resteasy;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import javax.ws.rs.Consumes;
import javax.ws.rs.CookieParam;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;

@Path("/hello")
public class Resource {

  public Resource() {}

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String hello() {
    return "RestEasy hello world example.";
  }

  @Path("/bypathparam/{name}")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String byPathParam(@PathParam("name") String name) {
    return "RestEasy: hello " + name;
  }

  @Path("/byqueryparam")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String byQueryParam(@QueryParam("param") String param) {
    return "RestEasy: hello " + param;
  }

  @Path("/byheader")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String byHeader(@HeaderParam("X-Custom-header") String param) {
    return "RestEasy: hello " + param;
  }

  @Path("/bycookie")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String byCookie(@CookieParam("cookieName") String param) {
    return "RestEasy: hello " + param;
  }

  @Path("/collection")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String collectionByQueryParam(@QueryParam("param") List<String> param) {
    return "RestEasy: hello " + param;
  }

  @Path("/set")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String setByQueryParam(@QueryParam("param") Set<String> param) {
    return "RestEasy: hello " + param;
  }

  @Path("/sortedset")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String sortedSetByQueryParam(@QueryParam("param") SortedSet<String> param) {
    return "RestEasy: hello " + param;
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
  @POST
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  public Response apiSecurityResponse(RequestBody input) {
    return Response.ok(input).build();
  }

  @GET
  @Path("/api_security/sampling/{i}")
  public Response apiSecuritySamplingWithStatus(@PathParam("i") int i) {
    return Response.status(i).header("content-type", "text/plain").entity("Hello!\n").build();
  }
}
