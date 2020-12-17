package datadog.smoketest.osgi.subscribing;

import datadog.smoketest.osgi.messaging.SubscriberSupport;
import java.util.Properties;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

public class Activator implements BundleActivator {
  private AsyncSubscriber subscriber;
  private ServiceRegistration subscriberRegistration;

  public void start(final BundleContext bundleContext) throws Exception {
    subscriber = new AsyncSubscriber();
    subscriber.start();

    subscriberRegistration =
        bundleContext.registerService(
            SubscriberSupport.class.getName(), subscriber, new Properties());
  }

  public void stop(final BundleContext bundleContext) throws Exception {
    if (null != subscriberRegistration) {
      subscriberRegistration.unregister();
      subscriberRegistration = null;
    }
    if (null != subscriber) {
      subscriber.stop();
      subscriber = null;
    }
  }
}
