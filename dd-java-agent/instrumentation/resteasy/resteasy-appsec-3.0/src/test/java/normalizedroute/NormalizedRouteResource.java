package normalizedroute;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/**
 * JAX-RS routes for the normalized route tests, outside the agent-ignored datadog.trace package.
 */
@Path("/")
public class NormalizedRouteResource {

  @GET
  @Path("files/{name}.json")
  public String file(@PathParam("name") String name) {
    return name;
  }

  @GET
  @Path("three/{a}-{b}-{c}")
  public String three(@PathParam("a") String a) {
    return a;
  }

  @GET
  @Path("users/user-{id}")
  public String user(@PathParam("id") String id) {
    return id;
  }

  @GET
  @Path("regex/{id: [0-9]+}")
  public String regex(@PathParam("id") String id) {
    return id;
  }

  @GET
  @Path("catch-all/{path: .*}")
  public String catchAll(@PathParam("path") String path) {
    return "[" + path + "]";
  }

  @GET
  @Path("param-names/{user-id}/{user_id}")
  public String paramNames(@PathParam("user-id") String first) {
    return first;
  }

  @GET
  @Path("encoded/caf\u00e9")
  public String encoded() {
    return "encoded";
  }

  /** Sub-resource locator: a route but no http method. */
  @Path("locator/{id}")
  public SubResource locator() {
    return new SubResource();
  }

  /** The sub-resource {@link #locator()} returns. */
  public static class SubResource {
    @GET
    @Path("{name}")
    public String get(@PathParam("name") String name) {
      return name;
    }
  }
}
