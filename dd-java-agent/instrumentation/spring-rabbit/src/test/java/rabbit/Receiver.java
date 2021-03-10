package rabbit;

import datadog.trace.api.Trace;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.concurrent.CountDownLatch;
import org.springframework.stereotype.Component;

@Component
public class Receiver {

  public final CountDownLatch latch = new CountDownLatch(1);

  @Trace(operationName = "receive")
  public void receiveMessage(String message) {
    assert null != AgentTracer.activeSpan() : "no active span during message receipt";
    latch.countDown();
  }
}
