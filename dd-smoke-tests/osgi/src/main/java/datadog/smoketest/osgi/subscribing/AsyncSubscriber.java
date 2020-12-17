package datadog.smoketest.osgi.subscribing;

import datadog.smoketest.osgi.messaging.Message;
import datadog.smoketest.osgi.messaging.SubscriberSupport;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AsyncSubscriber extends SubscriberSupport {
  private ExecutorService executorService;

  @Override
  protected void doStart() {
    executorService = Executors.newCachedThreadPool();
  }

  @Override
  protected void doReceive(final Message message) {
    executorService.submit(new ReceiveTask(message));
  }

  @Override
  protected void doStop() {
    executorService.shutdown();
    executorService = null;
  }

  class ReceiveTask implements Runnable {
    private final Message message;

    ReceiveTask(final Message message) {
      this.message = message;
    }

    @Override
    public void run() {
      log.info("Received {}", message);
    }
  }
}
