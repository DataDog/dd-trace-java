package datadog.opentracing.resolver;

import com.google.auto.service.AutoService;
import datadog.opentracing.DDTracer;
import datadog.trace.api.Config;
import io.opentracing.Tracer;
import io.opentracing.contrib.tracerresolver.TracerResolver;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AutoService(TracerResolver.class)
public class DDTracerResolver extends TracerResolver {

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
