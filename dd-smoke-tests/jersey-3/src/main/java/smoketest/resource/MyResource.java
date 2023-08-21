package smoketest.resource;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.sql.SQLException;
import smoketest.DB;

@Path("/hello")
public class MyResource {

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
}
