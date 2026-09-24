package datadog.telemetry.dependency;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

class DependencyTestHelper {

  private DependencyTestHelper() {}

  static File getJar(String jarName) {
    String path =
        ClassLoader.getSystemClassLoader()
            .getResource("datadog/telemetry/dependencies/" + jarName)
            .getPath();
    File jarFile = new File(path);
    assertTrue(jarFile.isFile());
    return jarFile;
  }
}
