package datadog.smoketest.jbossmodules.publishing;

import datadog.smoketest.jbossmodules.messaging.Message;
import datadog.smoketest.jbossmodules.messaging.PublisherSupport;
import datadog.smoketest.jbossmodules.messaging.SubscriberSupport;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AsyncPublisher extends PublisherSupport {
  private ExecutorService executorService;

  @Override
  protected void doStart() {
    executorService = Executors.newCachedThreadPool();
  }

  @Override
  protected void doPublish(final SubscriberSupport subscriber, final Message message) {
    executorService.submit(new PublishTask(subscriber, message));
  }

  @Override
  protected void doStop() {
    executorService.shutdown();
    executorService = null;
  }

  class PublishTask implements Runnable {
    private final SubscriberSupport subscriber;
    private final Message message;

    PublishTask(final SubscriberSupport subscriber, final Message message) {
      this.subscriber = subscriber;
      this.message = message;
    }

    @Override
    public void run() {
      subscriber.receive(message);
      log.info("Published " + message + " to " + subscriber);
    }
  }
}
