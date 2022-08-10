package datadog.telemetry.dependency

import datadog.trace.test.util.DDSpecification

abstract class DepSpecification extends DDSpecification {

  protected static File getJar(String jarName) {
    String path = ClassLoader.systemClassLoader.getResource("datadog/telemetry/dependencies/$jarName").path
    File jarFile = new File(path)
    assert jarFile.isFile()
    jarFile
  }
}
