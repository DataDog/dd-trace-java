package datadog.smoketest.osgi.publishing;

import datadog.smoketest.osgi.messaging.PublisherSupport;
import datadog.smoketest.osgi.messaging.SubscriberSupport;
import java.util.Properties;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;

public class Activator implements BundleActivator {
  private final Object publisherLock = new Object();
  private ServiceTracker subscriberTracker;

  private volatile AsyncPublisher publisher;
  private ServiceRegistration publisherRegistration;

  public void start(final BundleContext bundleContext) throws Exception {
    subscriberTracker =
        new ServiceTracker(bundleContext, SubscriberSupport.class.getName(), null) {
          @Override
          public Object addingService(final ServiceReference reference) {
            SubscriberSupport subscriber = (SubscriberSupport) bundleContext.getService(reference);
            if (null == publisher) {
              startPublisher(bundleContext, subscriber);
            } else {
              publisher.subscribe(subscriber);
            }
            return subscriber;
          }

          @Override
          public void removedService(final ServiceReference reference, final Object subscriber) {
            publisher.unsubscribe((SubscriberSupport) subscriber);
            bundleContext.ungetService(reference);
          }
        };
    subscriberTracker.open();
  }

  public void stop(final BundleContext bundleContext) throws Exception {
    subscriberTracker.close();
    subscriberTracker = null;

    synchronized (publisherLock) {
      if (null != publisherRegistration) {
        publisherRegistration.unregister();
        publisherRegistration = null;
      }
      if (null != publisher) {
        publisher.stop();
        publisher = null;
      }
    }
  }

  private void startPublisher(final BundleContext bundleContext, SubscriberSupport subscriber) {
    synchronized (publisherLock) {
      if (null == publisher) {
        try {
          AsyncPublisher publisher = new AsyncPublisher();
          publisher.start();
          publisher.subscribe(subscriber);
          publisherRegistration =
              bundleContext.registerService(
                  PublisherSupport.class.getName(), publisher, new Properties());
          this.publisher = publisher;
        } catch (Exception e) {
          throw new IllegalStateException("Publisher did not start", e);
        }
      }
    }
  }
}
