package datadog.trace.instrumentation.sofarpc;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("/greeter")
public interface RestGreeterService {
  @GET
  @Path("/hello/{name}")
  @Produces(MediaType.TEXT_PLAIN)
  String sayHello(@PathParam("name") String name);
}
