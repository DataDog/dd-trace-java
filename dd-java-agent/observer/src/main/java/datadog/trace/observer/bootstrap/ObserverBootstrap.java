package datadog.trace.observer.bootstrap;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/** Install the JDK-only bridge in bootstrap before any relocated early configuration reads. */
public final class ObserverBootstrap {
  private ObserverBootstrap() {}

  public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
    File jar =
        new File(
            ObserverBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(jar));
    Class.forName("datadog.trace.observer.bootstrap.ObserverRuntime", true, null)
        .getMethod("initializePremain")
        .invoke(null);
    Class.forName("datadog.trace.observer.trace.bootstrap.AgentPreCheck", true, null)
        .getMethod("premain", String.class, Instrumentation.class)
        .invoke(null, arguments, instrumentation);
  }
}
