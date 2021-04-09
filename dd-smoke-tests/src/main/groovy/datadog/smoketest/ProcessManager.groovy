package datadog.smoketest

import datadog.trace.agent.test.utils.PortUtils
import spock.lang.Shared
import spock.lang.Specification
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeoutException

abstract class ProcessManager extends Specification {

  public static final PROFILING_START_DELAY_SECONDS = 1
  public static final int PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS = 5
  public static final String SERVICE_NAME = "smoke-test-java-app"
  public static final String ENV = "smoketest"
  public static final String VERSION = "99"

  @Shared
  protected String workingDirectory = System.getProperty("user.dir")
  @Shared
  protected String buildDirectory = System.getProperty("datadog.smoketest.builddir")
  @Shared
  protected String shadowJarPath = System.getProperty("datadog.smoketest.agent.shadowJar.path")

  @Shared
  protected static int profilingPort = -1

  @Shared
  protected String[] defaultJavaProperties

  @Shared
  protected Process testedProcess

  /**
   * Will be initialized after calling {@linkplain AbstractSmokeTest#checkLog} and hold {@literal true}
   * if there are any ERROR or WARN lines in the test application log.
   */
  @Shared
  def logHasErrors

  @Shared
  def logFilePath = "${buildDirectory}/reports/testProcess.${this.getClass().getName()}.log"

  def setup() {
    // TODO: once java7 support is dropped use testedProcess.isAlive() instead
    try {
      testedProcess.exitValue()
      assert false: "Process not alive before test"
    } catch (IllegalThreadStateException ignored) {
      // expected
    }
  }

  def setupSpec() {
    if (buildDirectory == null || shadowJarPath == null) {
      throw new AssertionError("Expected system properties not found. Smoke tests have to be run from Gradle. Please make sure that is the case.")
    }
    assert Files.isDirectory(Paths.get(buildDirectory))
    assert Files.isRegularFile(Paths.get(shadowJarPath))

    ProcessBuilder processBuilder = createProcessBuilder()

    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"))
    processBuilder.environment().put("DD_API_KEY", apiKey())

    processBuilder.redirectErrorStream(true)
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(new File(logFilePath)))

    testedProcess = processBuilder.start()
  }

  String javaPath() {
    final String separator = System.getProperty("file.separator")
    return System.getProperty("java.home") + separator + "bin" + separator + "java"
  }

  def cleanupSpec() {
    int maxAttempts = 10
    Integer exitValue
    for (int attempt = 1; attempt <= maxAttempts != null; attempt++) {
      try {
        exitValue = testedProcess?.exitValue()
        break
      }
      catch (Throwable e) {
        if (attempt == 1) {
          System.out.println("Destroying instrumented process")
          testedProcess.destroy()
        }
        if (attempt == maxAttempts - 1) {
          System.out.println("Destroying instrumented process (forced)")
          testedProcess.destroyForcibly()
        }
        sleep 1_000
      }
    }

    if (exitValue != null) {
      System.out.println("Instrumented process exited with " + exitValue)
    } else if (testedProcess != null) {
      throw new TimeoutException("Instrumented process failed to exit")
    }
  }

  def getProfilingUrl() {
    if (profilingPort == -1) {
      profilingPort = PortUtils.randomOpenPort()
    }
    return "http://localhost:${profilingPort}/"
  }

  /**
   * Check the test application log and set {@linkplain AbstractSmokeTest#logHasErrors} variable
   * @param checker custom closure to run on each log line
   */
  def checkLog(Closure checker) {
    new File(logFilePath).eachLine {
      if (it.contains("ERROR") || it.contains("ASSERTION FAILED")) {
        println it
        logHasErrors = true
      }
      checker(it)
    }
    if (logHasErrors) {
      println "Test application log is containing errors. See full run logs in ${logFilePath}"
    }
  }

  def checkLog() {
    checkLog {}
  }

  abstract ProcessBuilder createProcessBuilder()

  String apiKey() {
    return "01234567890abcdef123456789ABCDEF"
  }
}
