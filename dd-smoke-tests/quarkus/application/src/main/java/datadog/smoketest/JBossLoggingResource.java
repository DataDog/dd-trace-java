package datadog.smoketest;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
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
