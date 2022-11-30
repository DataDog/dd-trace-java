package datadog.smoketest.systemloader;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

public class TestHelper {
  @SuppressForbidden
  static URL[] classPath() {
    String[] paths = System.getProperty("java.class.path").split(File.pathSeparator);
    URL[] classPath = new URL[paths.length];
    for (int i = 0; i < paths.length; i++) {
      classPath[i] = toURL(paths[i]);
    }
    return classPath;
  }

  static URL toURL(String path) {
    try {
      return new File(path).toURI().toURL();
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
