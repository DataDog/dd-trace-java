package datadog.smoketest;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

@Path("/hello-jboss")
public class JBossLoggingResource {
  Logger log = Logger.getLogger(JBossLoggingResource.class);

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String hello(@DefaultValue("0") @QueryParam("id") int id) {
    Tracer tracer = GlobalTracer.get();
    log.debug("TT|" + tracer.getTraceId() + "|TS|" + tracer.getSpanId());
    return "Hello " + id + "!";
  }
}
