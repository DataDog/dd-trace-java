package datadog.smoketest.rum.tomcat10

import datadog.smoketest.rum.AbstractRumServerSmokeTest
import datadog.trace.api.Platform

class Tomcat10RumSmokeTest extends AbstractRumServerSmokeTest {
  @Override
  ProcessBuilder createProcessBuilder() {
    String jarPath = System.getProperty('datadog.smoketest.rum.tomcat10.shadowJar.path')

    List<String> command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(defaultRumProperties)
    if (Platform.isJavaVersionAtLeast(17)) {
      command.addAll((String[]) ['--add-opens', 'java.base/java.lang=ALL-UNNAMED'])
    }
    command.addAll(['-jar', jarPath, Integer.toString(httpPort)])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }
}
