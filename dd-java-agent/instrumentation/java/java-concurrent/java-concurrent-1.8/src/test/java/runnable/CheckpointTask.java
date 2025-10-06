package runnable;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.Trace;
import java.util.concurrent.CountDownLatch;

public class CheckpointTask implements Runnable {

  private final boolean traceChild;
  private final CountDownLatch latch;

  public CheckpointTask(boolean traceChild) {
    this(traceChild, new CountDownLatch(0));
  }

  public CheckpointTask(boolean traceChild, CountDownLatch latch) {
    this.traceChild = traceChild;
    this.latch = latch;
  }

  @Override
  public void run() {
    if (traceChild) {
      traceableChild();
    }
    latch.countDown();
  }

  @Trace
  private void traceableChild() {
    assert null != activeSpan();
  }
}
