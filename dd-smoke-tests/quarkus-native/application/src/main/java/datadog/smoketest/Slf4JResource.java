package datadog.smoketest;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/hello-slf4j")
public class Slf4JResource {
  Logger log = LoggerFactory.getLogger(Slf4JResource.class);

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String hello(@DefaultValue("0") @QueryParam("id") int id) {
    Tracer tracer = GlobalTracer.get();
    log.debug("TT|" + tracer.getTraceId() + "|TS|" + tracer.getSpanId());
    return "Hello " + id + "!";
  }
}
