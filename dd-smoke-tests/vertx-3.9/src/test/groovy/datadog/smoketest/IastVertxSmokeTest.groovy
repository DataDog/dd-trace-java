package datadog.smoketest


import groovy.transform.CompileDynamic

import static datadog.trace.api.config.IastConfig.*

@CompileDynamic
class IastVertxSmokeTest extends AbstractIastVertxSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    final vertxUberJar = System.getProperty('datadog.smoketest.vertx.uberJar.path')
    final command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      //'-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005',
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
      '-Ddd.app.customlogmanager=true',
      "-Dvertx.http.port=${httpPort}",
      '-cp',
      vertxUberJar,
      'datadog.vertx_3_9.IastVerticle'
    ])
    final processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }
}
