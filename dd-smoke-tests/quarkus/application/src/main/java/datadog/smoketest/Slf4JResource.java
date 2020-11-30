package datadog.smoketest;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
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
