package datadog.opentracing.resolver;

import com.google.auto.service.AutoService;
import datadog.opentracing.DDTracer;
import datadog.trace.api.Config;
import io.opentracing.Tracer;
import io.opentracing.contrib.tracerresolver.TracerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(TracerFactory.class)
public class DDTracerFactory implements TracerFactory {

  private static final Logger log = LoggerFactory.getLogger(DDTracerFactory.class);

  @Override
  public Tracer getTracer() {
    if (Config.get().isTraceResolverEnabled()) {
      log.info("Creating DDTracer with DDTracerFactory");
      return DDTracer.builder().build();
    } else {
      log.info("DDTracerFactory disabled");
      return null;
    }
  }
}
