package datadog.vertx_3_9;

import static datadog.vertx_3_9.MainVerticle.randomFactorial;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

@Path("/factorial")
public class ResteasyResource {
  @GET
  @Produces("text/plain")
  public String factorial() {
    return randomFactorial().toString();
  }
}
