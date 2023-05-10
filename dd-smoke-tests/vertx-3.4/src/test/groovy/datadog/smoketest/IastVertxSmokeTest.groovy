package datadog.smoketest

import groovy.transform.CompileDynamic
import spock.lang.IgnoreIf

import static datadog.trace.api.config.IastConfig.*

@CompileDynamic
@IgnoreIf({
  // TODO https://github.com/eclipse-vertx/vert.x/issues/2172
  new BigDecimal(System.getProperty("java.specification.version")).isAtLeast(17.0) })
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
      withSystemProperty(IAST_REQUEST_SAMPLING, 100),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
      '-Ddd.app.customlogmanager=true',
      "-Dvertx.http.port=${httpPort}",
      '-cp',
      vertxUberJar,
      'datadog.vertx_3_4.IastVerticle'
    ])
    final processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }
}
