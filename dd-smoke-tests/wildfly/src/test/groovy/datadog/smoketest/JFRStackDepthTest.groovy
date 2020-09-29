package datadog.smoketest

import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.agent.test.utils.PortUtils
import org.junit.Assume
import spock.lang.AutoCleanup
import spock.lang.Retry
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

@Retry(delay = 2000)
class JFRStackDepthTest extends Specification {
  public static final PROFILING_START_DELAY_SECONDS = 1
  public static final int PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS = 5

  @Shared
  def wildflyDirectory = new File(System.getProperty("datadog.smoketest.wildflyDir"))
  @Shared
  def buildDirectory = System.getProperty("datadog.smoketest.builddir")
  @Shared
  def shadowJarPath = System.getProperty("datadog.smoketest.agent.shadowJar.path")

  @AutoCleanup
  TestHttpServer server = httpServer {
    handlers {
      all {
        requests.add(request)
        response.status(200).send()
      }
    }
  }

  @Shared
  BlockingQueue<TestHttpServer.HandlerApi.RequestApi> requests = new LinkedBlockingQueue<>()

  def httpsPort = PortUtils.randomOpenPort()
  def httpPort = PortUtils.randomOpenPort()
  def managementPort = PortUtils.randomOpenPort()
  Process testedProcess

  def setup() {
    requests.clear()
    server.start()
  }

  def cleanup() {
    ProcessBuilder processBuilder = new ProcessBuilder(
      "${wildflyDirectory}/bin/jboss-cli.sh",
      "--connect",
      "--controller=localhost:${managementPort}",
      "command=:shutdown")
    processBuilder.directory(wildflyDirectory)
    Process process = processBuilder.start()
    process.waitFor()
  }

  long startWildfly(String extraOpts) {
    def logFilePath = "${buildDirectory}/reports/testProcess.${specificationContext.currentIteration.name}.log"
    String[] javaOpts = [
      "-javaagent:${shadowJarPath}",
      "-Ddd.trace.agent.port=${server.address.port}",
      "-Ddd.service.name=smoke-test-java-app",
      "-Ddd.profiling.enabled=true",
      "-Ddd.profiling.start-delay=${PROFILING_START_DELAY_SECONDS}",
      "-Ddd.profiling.upload.period=${PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS}",
      "-Ddd.profiling.url=http://localhost:${server.address.port}",
      "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
      "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug",
      " -Djboss.http.port=${httpPort} -Djboss.https.port=${httpsPort}",
      " -Djboss.management.http.port=${managementPort}",
      extraOpts,
    ]

    ProcessBuilder processBuilder =
      new ProcessBuilder("${wildflyDirectory}/bin/standalone.sh")
    processBuilder.directory(wildflyDirectory)
    processBuilder.environment().put("JAVA_OPTS", javaOpts.join(" "))
    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"))
    processBuilder.environment().put("DD_API_KEY", "01234567890abcdef123456789ABCDEF")
    processBuilder.redirectErrorStream(true)
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(new File(logFilePath)))
    testedProcess = processBuilder.start()

    PortUtils.waitForPortToOpen(httpPort, 240, TimeUnit.SECONDS, testedProcess)

    // This test can't run on Java 1.8, requires Process::children and Process::pid only present since 1.9
    Assume.assumeFalse(System.getProperty("java.specification.version") == "1.8")

    // can't use testedProcess.pid() as that's just the pid for standalone.sh
    return testedProcess.children().findFirst().get().pid()
  }

  int readJFRStackdepth(long pid) {
    // using VM args exposing a JMX server to query this configuration crashes JBoss
    // thus the external process call with jcmd
    def jcmdBin = System.getProperty("java.home") + "/bin/jcmd"
    def jcmdProc = new ProcessBuilder(jcmdBin, "${pid}", "JFR.configure").start()
    jcmdProc.waitForOrKill(1000)

    return jcmdProc.text.lines()
      .map({ it =~ /^Stack depth: (\d+)/ })
      .filter({ it.size() > 0 })
      .map({ it[0][1].toInteger() })
      .findFirst()
      .get() as int
  }

  def "verify JFR.configure stackdepth"() {
    setup:
    def wildflyPid = startWildfly(extraOpts)

    expect:
    new PollingConditions(timeout: 10).eventually {
      assert expected == readJFRStackdepth(wildflyPid)
    }

    where:
    extraOpts                                                         | expected
    ""                                                                | 256
    "-XX:FlightRecorderOptions=stackdepth=512"                        | 512
    "-XX:FlightRecorderOptions=globalbuffersize=10MB,stackdepth=3000" | 1024
  }
}
