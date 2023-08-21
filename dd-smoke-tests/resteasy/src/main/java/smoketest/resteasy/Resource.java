package smoketest.resteasy;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import javax.ws.rs.CookieParam;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

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
  public String byPathParam(@PathParam("name") String name) throws SQLException {
    DB.store(name);
    return "RestEasy: hello " + name;
  }

  @Path("/byqueryparam")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String byQueryParam(@QueryParam("param") String param) throws SQLException {
    DB.store(param);
    return "RestEasy: hello " + param;
  }

  @Path("/byheader")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String byHeader(@HeaderParam("X-Custom-header") String param) throws SQLException {
    DB.store(param);
    return "RestEasy: hello " + param;
  }

  @Path("/bycookie")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String byCookie(@CookieParam("cookieName") String param) throws SQLException {
    DB.store(param);
    return "RestEasy: hello " + param;
  }

  @Path("/collection")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String collectionByQueryParam(@QueryParam("param") List<String> param)
      throws SQLException {
    DB.store(param.get(0));
    return "RestEasy: hello " + param;
  }

  @Path("/set")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String setByQueryParam(@QueryParam("param") Set<String> param) throws SQLException {
    DB.store(param.iterator().next());
    return "RestEasy: hello " + param;
  }

  @Path("/sortedset")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String sortedSetByQueryParam(@QueryParam("param") SortedSet<String> param)
      throws SQLException {
    DB.store(param.iterator().next());
    return "RestEasy: hello " + param;
  }
}
