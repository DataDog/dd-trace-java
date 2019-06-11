package datadog.smoketest

import datadog.trace.agent.test.utils.PortUtils
import spock.lang.Shared
import spock.lang.Specification

abstract class AbstractSmokeTest extends Specification {

  public static final PROFILING_API_KEY = "org2_api_key"
  public static final int PROFILING_RECORDING_DURATION_SECONDS = 9
  public static final int PROFILING_RECORDING_PERIOD_SECONDS = 10

  @Shared
  protected String workingDirectory = System.getProperty("user.dir")
  @Shared
  protected String buildDirectory = System.getProperty("datadog.smoketest.builddir")
  @Shared
  protected String shadowJarPath = System.getProperty("datadog.smoketest.agent.shadowJar.path")
  @Shared
  protected int profilingPort
  @Shared
  protected String profilingUrl
  @Shared
  protected String[] defaultJavaProperties

  @Shared
  protected Process serverProcess

  def setupSpec() {
    if (buildDirectory == null || shadowJarPath == null) {
      throw new AssertionError("Expected system properties not found. Smoke tests have to be run from Gradle. Please make sure that is the case.")
    }

    profilingPort = PortUtils.randomOpenPort()
    profilingUrl = "http://localhost:${profilingPort}/api/v0/profiling/chunk"

    defaultJavaProperties = [
      "-javaagent:${shadowJarPath}",
      "-Ddd.writer.type=LoggingWriter",
      "-Ddd.service.name=smoke-test-java-app",
      "-Ddd.profiling.enabled=true",
      "-Ddd.profiling.periodic.duration=${PROFILING_RECORDING_DURATION_SECONDS}",
      "-Ddd.profiling.periodic.period=${PROFILING_RECORDING_PERIOD_SECONDS}",
      "-Ddd.profiling.periodic.delay=0",
      "-Ddd.profiling.url=http://localhost:${profilingPort}/api/v0/profiling/chunk",
      "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
      "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug"
    ]

    ProcessBuilder processBuilder = createProcessBuilder()

    processBuilder.environment().put("DD_PROFILING_APIKEY", PROFILING_API_KEY)

    processBuilder.redirectErrorStream(true)
    File log = new File("${buildDirectory}/reports/serverProcess.log")
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(log))

    serverProcess = processBuilder.start()
  }

  def cleanupSpec() {
    serverProcess?.waitForOrKill(1)
  }

  abstract ProcessBuilder createProcessBuilder()
}
