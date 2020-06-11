package datadog.smoketest

import okhttp3.mockwebserver.MockWebServer
import spock.lang.Shared

abstract class AbstractProfilingIntegrationTest extends AbstractSmokeTest {
  // can not be @Shared since the same instance will be reused for all specs and this is not supported by MockWebServer
  protected static MockWebServer profilingServer

  @Override
  ProcessBuilder createProcessBuilder() {
    String profilingShadowJar = System.getProperty("datadog.smoketest.profiling.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) ["-jar", profilingShadowJar])
    command.add(Integer.toString(exitDelay))
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }

  @Override
  def startServer() {
    profilingServer = new MockWebServer()
    profilingServer.start()
    profilingPort = profilingServer.getPort()
  }

  @Override
  def stopServer() {
    try {
      profilingServer.shutdown()
    } catch (final IOException e) {
      // Looks like this happens for some unclear reason, but should not affect tests
    }
  }

  def getExitDelay() {
    return -1
  }
}
