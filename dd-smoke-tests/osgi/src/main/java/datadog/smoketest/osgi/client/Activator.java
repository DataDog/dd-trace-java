package datadog.smoketest.osgi.client;

import java.util.Properties;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

public class Activator implements BundleActivator {
  private MessageClient messageClient;
  private ServiceRegistration subscriberRegistration;

  @Override
  public void start(final BundleContext bundleContext) throws Exception {
    messageClient = new MessageClient();
    messageClient.start();

    subscriberRegistration =
        bundleContext.registerService(
            MessageClient.class.getName(), messageClient, new Properties());
  }

  @Override
  public void stop(final BundleContext bundleContext) throws Exception {
    if (null != subscriberRegistration) {
      subscriberRegistration.unregister();
      subscriberRegistration = null;
    }
    if (null != messageClient) {
      messageClient.stop();
      messageClient = null;
    }
  }
}
