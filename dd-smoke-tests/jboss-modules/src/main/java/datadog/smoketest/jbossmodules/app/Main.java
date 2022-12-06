package datadog.smoketest.jbossmodules.app;

import datadog.smoketest.jbossmodules.messaging.ClientSupport;
import datadog.smoketest.jbossmodules.messaging.PublisherSupport;
import datadog.smoketest.jbossmodules.messaging.SubscriberSupport;
import org.jboss.modules.Module;

public class Main {
  public static void main(final String[] args) throws Exception {
    ClientSupport client =
        Module.getCallerModule().loadService(ClientSupport.class).iterator().next();
    PublisherSupport publisher =
        Module.getCallerModule().loadService(PublisherSupport.class).iterator().next();
    SubscriberSupport subscriber =
        Module.getCallerModule().loadService(SubscriberSupport.class).iterator().next();

    publisher.subscribe(subscriber);

    client.start();
    publisher.start();
    subscriber.start();

    publisher.publish("Hello, world!");

    publisher.stop();
    subscriber.stop();
    client.stop();
  }
}
