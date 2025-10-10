import datadog.opentracing.DDTracer;
import io.opentracing.util.GlobalTracer;
import java.nio.file.Files;
import java.nio.file.Path;
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

  @Test
  void buildTracerWithUDS() throws Exception {
    // trigger use of JNR and JFFI to validate native method linking
    Path fakeSocketPath = Files.createTempFile("dd-java", "test-uds");
    System.setProperty("dd.trace.agent.url", "unix://" + fakeSocketPath.toUri().getPath());
    try {
      // we don't need to actually use the tracer, just build it
      DDTracer.builder().serviceName("TestServiceWithUDS").build();
    } finally {
      System.clearProperty("dd.trace.agent.url");
      Files.delete(fakeSocketPath);
    }
  }
}
