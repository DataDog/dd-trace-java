package datadog.smoketest

import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.agent.test.utils.PortUtils
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.lang.management.ManagementFactory
import java.lang.management.ThreadInfo
import java.lang.management.ThreadMXBean
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

abstract class AbstractSmokeTest extends Specification {

  public static final PROFILING_START_DELAY_SECONDS = 1
  public static final int PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS = 5

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

  @Shared
  protected BlockingQueue<TestHttpServer.HandlerApi.RequestApi> traceRequests = new LinkedBlockingQueue<>()

  /**
   * Will be initialized after calling {@linkplain AbstractSomeTest#checkLog} and hold {@literal true}
   * if there are any ERROR or WARN lines in the test application log.
   */
  @Shared
  def logHasErrors

  @Shared
  def logFilePath = "${buildDirectory}/reports/testProcess.${this.getClass().getName()}.log"

  @Shared
  @AutoCleanup
  protected TestHttpServer server = httpServer {
    handlers {
      prefix("/v0.4/traces") {
        println("Received traces: " + request.getHeader("X-Datadog-Trace-Count"))
        traceRequests.add(request)
        response.status(200).send()
      }
    }
  }

  def setup() {
    traceRequests.clear()
  }

  def setupSpec() {
    if (buildDirectory == null || shadowJarPath == null) {
      throw new AssertionError("Expected system properties not found. Smoke tests have to be run from Gradle. Please make sure that is the case.")
    }

    startServer()
    System.out.println("Mock agent started at " + server.address)

    defaultJavaProperties = [
      "-javaagent:${shadowJarPath}",
      "-Ddd.trace.agent.port=${server.address.port}",
      "-Ddd.service.name=smoke-test-java-app",
      "-Ddd.profiling.enabled=true",
      "-Ddd.profiling.start-delay=${PROFILING_START_DELAY_SECONDS}",
      "-Ddd.profiling.upload.period=${PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS}",
      "-Ddd.profiling.url=${getProfilingUrl()}",
      "-Ddd.jmxfetch.enabled=false",
      "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
      "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug"
    ]

    ProcessBuilder processBuilder = createProcessBuilder()

    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"))
    processBuilder.environment().put("DD_API_KEY", apiKey())

    processBuilder.redirectErrorStream(true)
    File log = new File("${buildDirectory}/reports/testProcess.${this.getClass().getName()}.log")
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(log))

    testedProcess = processBuilder.start()
  }

  String javaPath() {
    final String separator = System.getProperty("file.separator")
    return System.getProperty("java.home") + separator + "bin" + separator + "java"
  }

  def cleanupSpec() {
    testedProcess?.waitForOrKill(1)
    stopServer()
    threadDump()
  }

  private static String threadDump() {
    StringBuffer threadDump = new StringBuffer(System.lineSeparator())
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean()
    for (ThreadInfo threadInfo : threadMXBean.dumpAllThreads(true, true)) {
      threadDump.append(threadInfo.toString())
    }
    System.out.println("!!!" + threadDump.toString())
  }

  def getProfilingUrl() {
    if (profilingPort == -1) {
      profilingPort = PortUtils.randomOpenPort()
    }
    return "http://localhost:${profilingPort}/"
  }

  def startServer() {
    server.start()
  }

  def stopServer() {
    // do nothing; 'server' is autocleanup
    server.stop()
    server.close()
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
