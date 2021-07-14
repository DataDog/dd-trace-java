package datadog.trace.common.writer.ddagent;

import java.util.concurrent.CountDownLatch;

public final class FlushEvent {
  private final CountDownLatch latch;

  public FlushEvent(CountDownLatch latch) {
    this.latch = latch;
  }

  public void sync() {
    latch.countDown();
  }
}
