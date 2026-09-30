package datadog.gradle.plugin.observer;

import java.io.File;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.jar.JarFile;

/** Resolve the observer actually attached to this JVM, without caller artifact metadata. */
public final class ObserverAgentSelection {
  private ObserverAgentSelection() {}

  public static String configurationFingerprint() throws Exception {
    return (String)
        Class.forName("datadog.trace.observer.bootstrap.ObserverRuntime", false, null)
            .getMethod("configurationFingerprint")
            .invoke(null);
  }

  public static File attached() throws Exception {
    Class<?> runtime;
    try {
      runtime = Class.forName("datadog.trace.observer.bootstrap.ObserverRuntime", false, null);
    } catch (ClassNotFoundException absent) {
      return null;
    }
    URL resource = runtime.getResource("ObserverRuntime.class");
    if (resource == null || !resource.getProtocol().equals("jar")) {
      throw new IllegalStateException("Attached observer has no jar resource");
    }
    File jar =
        new File(((JarURLConnection) resource.openConnection()).getJarFileURL().toURI())
            .getCanonicalFile();
    try (JarFile file = new JarFile(jar)) {
      if (!"2"
          .equals(file.getManifest().getMainAttributes().getValue("Tracing-The-Tracer-Observer"))) {
        throw new IllegalArgumentException("Not an observer artifact");
      }
    }
    return jar;
  }

  public static File validate() throws Exception {
    File jar = attached();
    if (jar == null) {
      throw new IllegalArgumentException(
          "traceTracer requires an observer attached to the Gradle daemon with -javaagent");
    }
    return jar;
  }
}
