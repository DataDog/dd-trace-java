package datadog.opentracing.resolver;

import com.google.auto.service.AutoService;
import datadog.opentracing.DDTracer;
import datadog.trace.api.Config;
import io.opentracing.Tracer;
import io.opentracing.contrib.tracerresolver.TracerResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(TracerResolver.class)
public class DDTracerResolver extends TracerResolver {

  private static final Logger log = LoggerFactory.getLogger(DDTracerResolver.class);

  @Override
  protected Tracer resolve() {
    if (Config.get().isTraceResolverEnabled()) {
      log.info("Creating DDTracer with DDTracerResolver");
      return DDTracer.builder().build();
    } else {
      log.info("DDTracerResolver disabled");
      return null;
    }
  }
}
