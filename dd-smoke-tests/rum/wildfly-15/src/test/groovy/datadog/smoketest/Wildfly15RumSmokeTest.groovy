package datadog.smoketest

import datadog.smoketest.rum.AbstractRumServerSmokeTest
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.PortUtils
import okhttp3.Request
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

class Wildfly15RumSmokeTest extends AbstractRumServerSmokeTest {

  @Shared
  File wildflyDirectory = new File(System.getProperty("datadog.smoketest.wildflyDir"))
  @Shared
  int httpsPort = PortUtils.randomOpenPort()
  @Shared
  int managementPort = PortUtils.randomOpenPort()

  @Override
  ProcessBuilder createProcessBuilder() {
    ProcessBuilder processBuilder =
      new ProcessBuilder("${wildflyDirectory}/bin/standalone.sh")
    processBuilder.directory(wildflyDirectory)
    List<String> javaOpts = [
      *defaultJavaProperties,
      *defaultRumProperties,
      "-Djboss.http.port=${httpPort}",
      "-Djboss.https.port=${httpsPort}",
      "-Djboss.management.http.port=${managementPort}",
    ]
    processBuilder.environment().put("JAVA_OPTS", javaOpts.collect({ it.replace(' ', '\\ ') }).join(' '))
    return processBuilder
  }

  def setupSpec() {
    //wait for the deployment
    new PollingConditions(timeout: 300, delay: 2).eventually {
      assert OkHttpUtils.client().newCall(new Request.Builder().url("http://localhost:$httpPort/war/html").build()).execute().code() == 200
    }
  }

  def cleanupSpec() {
    ProcessBuilder processBuilder = new ProcessBuilder(
      "${wildflyDirectory}/bin/jboss-cli.sh",
      "--connect",
      "--controller=localhost:${managementPort}",
      "command=:shutdown")
    processBuilder.directory(wildflyDirectory)
    Process process = processBuilder.start()
    process.waitFor()
  }

  @Override
  String mountPoint() {
    "/war"
  }
}
