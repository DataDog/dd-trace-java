package datadog.trace.common.writer.ddagent;

import java.util.concurrent.CountDownLatch;

final class FlushEvent {
  private final CountDownLatch latch;

  FlushEvent(CountDownLatch latch) {
    this.latch = latch;
  }

  void sync() {
    latch.countDown();
  }
}
