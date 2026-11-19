package datadog.smoketest.appsec.dropwizard5;

import datadog.appsec.api.blocking.Blocking;
import datadog.appsec.api.blocking.BlockingException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URL;
import java.util.Map;

/** Jersey resource served under {@code /api/*}. */
@Path("/")
public class AppResource {

  @GET
  @Path("hello")
  @Produces(MediaType.TEXT_PLAIN)
  public String hello() {
    return "OK";
  }

  /** Returns a 404 built by the application. */
  @GET
  @Path("nfd-probe-status")
  public Response nfdProbeStatus() {
    return Response.status(404).build();
  }

  /** Returns a 404 mapped by Jersey from an exception. */
  @GET
  @Path("nfd-probe-throw")
  public Response nfdProbeThrow() {
    throw new NotFoundException();
  }

  @POST
  @Path("json")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, Object> json(final Map<String, Object> body) {
    return body;
  }

  @GET
  @Path("ssrf")
  @Produces(MediaType.TEXT_PLAIN)
  public String ssrf(@QueryParam("domain") final String domain) {
    try {
      new URL("http://" + domain).openStream().close();
    } catch (final BlockingException e) {
      throw e;
    } catch (final Throwable e) {
      // ignore errors opening connection
    }
    return "EXECUTED";
  }

  @GET
  @Path("user")
  @Produces(MediaType.TEXT_PLAIN)
  public String user(@QueryParam("id") final String id) {
    Blocking.forUser(id).blockIfMatch();
    return "OK";
  }
}
