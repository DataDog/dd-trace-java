package datadog.smoketest.jbossmodules.messaging;

import datadog.smoketest.jbossmodules.common.ServiceSupport;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class PublisherSupport extends ServiceSupport {
  private final List<SubscriberSupport> subscribers = new CopyOnWriteArrayList<>();

  public final void subscribe(final SubscriberSupport subscriber) {
    subscribers.add(subscriber);
  }

  public final void unsubscribe(final SubscriberSupport subscriber) {
    subscribers.remove(subscriber);
  }

  public final void publish(final String text) {
    publish(new TextMessage(text));
  }

  public final void publish(final Message message) {
    log.info("Publishing " + message);
    for (SubscriberSupport subscriber : subscribers) {
      doPublish(subscriber, message);
    }
  }

  protected abstract void doPublish(final SubscriberSupport subscriber, Message message);
}
