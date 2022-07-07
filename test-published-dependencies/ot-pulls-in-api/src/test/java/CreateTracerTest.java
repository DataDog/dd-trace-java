import datadog.opentracing.DDTracer;
import io.opentracing.util.GlobalTracer;
import org.junit.jupiter.api.Test;

public class CreateTracerTest {

  @Test
  void createTracer() throws InterruptedException {
    DDTracer tracer = DDTracer.builder().serviceName("TestService").build();
    GlobalTracer.registerIfAbsent(tracer);
    datadog.trace.api.GlobalTracer.registerIfAbsent(tracer);
    tracer.activateSpan(tracer.buildSpan("test-span").start()).close();
    // Sleep a bit so the trace sending machinery has a chance to start
    Thread.sleep(1000);
  }
}
