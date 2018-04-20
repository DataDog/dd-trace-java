package stackstate.opentracing.resolver;

import com.google.auto.service.AutoService;
import io.opentracing.Tracer;
import io.opentracing.contrib.tracerresolver.TracerResolver;
import io.opentracing.noop.NoopTracerFactory;
import io.opentracing.util.GlobalTracer;
import lombok.extern.slf4j.Slf4j;
import stackstate.opentracing.STSTracer;

@Slf4j
@AutoService(TracerResolver.class)
public class STSTracerResolver extends TracerResolver {

  public static Tracer registerTracer() {
    final Tracer tracer = TracerResolver.resolveTracer();

    if (tracer == null) {
      log.warn("Cannot resolved the tracer, use NoopTracer");
      return NoopTracerFactory.create();
    }

    log.info("Register the tracer via GlobalTracer");
    GlobalTracer.register(tracer);
    return tracer;
  }

  @Override
  protected Tracer resolve() {
    log.info("Creating the StackState Tracer from the resolver");

    return new STSTracer();
  }
}
