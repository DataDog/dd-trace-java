package datadog.smoketest

import static datadog.trace.api.config.IastConfig.*

class Jersey3SmokeTest extends AbstractJerseySmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String jarPath = System.getProperty('datadog.smoketest.jersey3.jar.path')

    List<String> command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll([
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
      withSystemProperty('integration.grizzly.enabled', true)
    ])
    //command.add("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000")
    //command.add("-Xdebug")
    command.addAll((String[]) ['-jar', jarPath, httpPort])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }
}
