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

  protected int numberOfProcesses() {
    return 1
  }

  @Shared
  protected int numberOfProcesses = numberOfProcesses()

  @Shared
  protected Process[] testedProcesses = new Process[numberOfProcesses]

  // Here for backwards compatibility with single process case
  @Shared
  protected Process testedProcess

  /**
   * Will be initialized after calling {@linkplain AbstractSmokeTest#checkLog} and hold {@literal true}
   * if there are any ERROR or WARN lines in the test application log.
   */
  @Shared
  def logHasErrors

  @Shared
  private String[] logFilePaths = (0..<numberOfProcesses).collect { idx ->
    "${buildDirectory}/reports/testProcess.${this.getClass().getName()}.${idx}.log"
  }

  // Here for backwards compatibility with single process case
  @Shared
  def logFilePath = logFilePaths[0]

  def setup() {
    testedProcesses.each { tp ->
      try {
        // TODO: once java7 support is dropped use testedProcess.isAlive() instead
        tp.exitValue()
        assert false: "Process not alive before test"
      } catch (IllegalThreadStateException ignored ) {
        // expected
      }
    }
  }

  def setupSpec() {
    if (buildDirectory == null || shadowJarPath == null) {
      throw new AssertionError("Expected system properties not found. Smoke tests have to be run from Gradle. Please make sure that is the case.")
    }
    assert Files.isDirectory(Paths.get(buildDirectory))
    assert Files.isRegularFile(Paths.get(shadowJarPath))

    beforeProcessBuilders()

    (0..<numberOfProcesses).each { idx ->
      ProcessBuilder processBuilder = createProcessBuilder(idx)


      processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"))
      processBuilder.environment().put("DD_API_KEY", apiKey())

      processBuilder.redirectErrorStream(true)
      processBuilder.redirectOutput(ProcessBuilder.Redirect.to(new File(logFilePaths[idx])))

      testedProcesses[idx] = processBuilder.start()
    }
    testedProcess = numberOfProcesses == 1 ? testedProcesses[0] : null
  }

  String javaPath() {
    final String separator = System.getProperty("file.separator")
    return System.getProperty("java.home") + separator + "bin" + separator + "java"
  }

  def cleanupSpec() {
    testedProcesses.each { tp ->
      int maxAttempts = 10
      Integer exitValue
      for (int attempt = 1; attempt <= maxAttempts != null; attempt++) {
        try {
          exitValue = tp?.exitValue()
          break
        }
        catch (Throwable e) {
          if (attempt == 1) {
            System.out.println("Destroying instrumented process")
            tp.destroy()
          }
          if (attempt == maxAttempts - 1) {
            System.out.println("Destroying instrumented process (forced)")
            tp.destroyForcibly()
          }
          sleep 1_000
        }
      }

      if (exitValue != null) {
        System.out.println("Instrumented process exited with " + exitValue)
      } else if (tp != null) {
        throw new TimeoutException("Instrumented process failed to exit")
      }
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
    logFilePaths.each { lfp ->
      def hasError = false
      new File(lfp).eachLine {
        if (it.contains("ERROR") || it.contains("ASSERTION FAILED")
          || it.contains("Failed to handle exception in instrumentation")) {
          println it
          hasError = logHasErrors = true
        }
        checker(it)
      }
      if (hasError) {
        println "Test application log contains errors. See full run logs in ${lfp}"
      }
    }
  }

  def checkLog() {
    checkLog {}
  }

  protected void beforeProcessBuilders() {}

  protected ProcessBuilder createProcessBuilder() {
    throw new IllegalArgumentException("Override createProcessBuilder() for single process tests")
  }

  protected ProcessBuilder createProcessBuilder(int processIndex) {
    if (processIndex > 0) {
      throw new IllegalArgumentException("Override createProcessBuilder(int processIndex) for multi process tests")
    }
    return createProcessBuilder()
  }

  String apiKey() {
    return "01234567890abcdef123456789ABCDEF"
  }
}
