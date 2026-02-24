package datadog.smoketest.osgi.app;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.util.tracker.ServiceTracker;

@SuppressForbidden
public class OSGiApplication {
  public static void main(final String[] args) throws Exception {
    Map<String, String> config = new HashMap(System.getProperties());

    String factoryType = System.getProperty("framework.factory");

    ClassLoader isolatingClassLoader = new IsolatingClassLoader();
    Thread.currentThread().setContextClassLoader(isolatingClassLoader);

    Framework framework = null;
    for (FrameworkFactory f : ServiceLoader.load(FrameworkFactory.class, isolatingClassLoader)) {
      if (factoryType.equals(f.getClass().getName())) {
        framework = f.newFramework(config);
        break;
      }
    }

    if (null == framework) {
      throw new IllegalArgumentException("Missing framework");
    }

    String bundlePaths = args[0];

    if (null == bundlePaths) {
      throw new IllegalArgumentException("Missing bundlePaths");
    }

    framework.start();

    List<Bundle> bundles = new ArrayList<>();

    BundleContext frameworkContext = framework.getBundleContext();
    for (String bundlePath : bundlePaths.split(",")) {
      System.out.println("Installing: " + bundlePath);
      bundles.add(frameworkContext.installBundle(new File(bundlePath).toURI().toString()));
    }

    for (Bundle bundle : bundles) {
      System.out.println("Starting: " + bundle + " " + bundle.getLocation());
      bundle.start();
    }

    ServiceTracker publisherTracker =
        new ServiceTracker(
            frameworkContext, "datadog.smoketest.osgi.messaging.PublisherSupport", null);
    publisherTracker.open();

    Object publisher = publisherTracker.waitForService(1_000);
    Method publish = publisher.getClass().getMethod("publish", String.class);
    publish.invoke(publisher, "Hello, world!");

    framework.stop();

    framework.waitForStop(1_000);

    // XXX: Knopflerfish will leave some dangling non-daemon thread and prevent shutdown here.
    System.exit(0);
  }
}
