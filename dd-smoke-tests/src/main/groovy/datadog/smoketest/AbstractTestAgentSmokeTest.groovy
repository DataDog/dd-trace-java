package datadog.smoketest

import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.PortUtils
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.testcontainers.containers.FixedHostPortGenericContainer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import spock.lang.Shared
import java.util.concurrent.TimeUnit
import static datadog.trace.test.util.ForkedTestUtils.getMaxMemoryArgumentForFork
import static datadog.trace.test.util.ForkedTestUtils.getMinMemoryArgumentForFork

abstract class AbstractTestAgentSmokeTest extends ProcessManager {

  @Shared
  protected String snapshotDirectory

  @Shared
  protected GenericContainer testAgent

  @Shared
  protected int testAgentMappedPort = PortUtils.randomOpenPort()

  @Shared
  protected int testAgentPort = 8126

  @Shared
  int httpPort = PortUtils.randomOpenPort()

  @Shared
  String[] defaultJavaProperties = [
    "${getMaxMemoryArgumentForFork()}",
    "${getMinMemoryArgumentForFork()}",
    "-javaagent:${shadowJarPath}",
    "-XX:ErrorFile=/tmp/hs_err_pid%p.log",
    "-Ddd.trace.agent.port=${testAgentMappedPort}",
    "-Ddd.service.name=${SERVICE_NAME}",
    "-Ddd.env=${ENV}",
    "-Ddd.version=${VERSION}",
    "-Ddd.profiling.enabled=false",
    "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
    "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug"
  ]

  protected OkHttpClient client = OkHttpUtils.client()

  def setupSpec() {
    //In CI, there a container will be standup so no need to use test containers
    PortUtils.waitForPortToOpen(httpPort, 240, TimeUnit.SECONDS, testedProcess)
    snapshotDirectory = "${workingDirectory}/snapshots"

    assert new File(snapshotDirectory).exists() : "snapshot directory doesn't exist"

    if ("true" != System.getenv("CI")) {
      testAgent = new FixedHostPortGenericContainer("kyleverhoog/dd-trace-test-agent:latest")
        .withFixedExposedPort(testAgentMappedPort, testAgentPort)
        .withEnv("SNAPSHOT_DIR", "/dd-smoke-tests")
        .withFileSystemBind(new File(workingDirectory).getParentFile().getAbsolutePath(), "/dd-smoke-tests")
        .waitingFor(
        Wait.forLogMessage(".*Started server on port.*\\n", 1)
        )
      testAgent.start()
      testAgentMappedPort = testAgent.getMappedPort(testAgentPort)
    }
  }

  /**
   * Assert trace structure by sending traces synchronously to the test agent
   * @param testTokenID the key of the test agent. For the test to work, a .snap file of the same name should exist in the /snapshots directory of the project folder
   * @param ignoredKeys list of keys in a trace snapshot that the test agent should ignore
   * @param func the command whose generated trace should be asserted by the testAgent
   */
  def snapshot(String testTokenID, String[] ignoredKeys = ['meta.http.url', 'meta.thread.name', 'meta.peer.port', 'meta.thread.id'], Closure func) {
    println "The user dir is " + snapshotDirectory
    //let test agent know the test is started
    def url = "http://localhost:${testAgentMappedPort}/test/start"
    def urlBuilder = HttpUrl.parse(url).newBuilder().addQueryParameter("token", testTokenID)

    def request = new Request.Builder().url(urlBuilder.build()).get().build()
    Response response = client.newCall(request).execute()

    assert response != null
    assert response.code() == 200

    func.call()

    Thread.sleep(1000)
    if (testAgent != null) {
      printLogToFile(testTokenID)
    }

    //resend the test agent results to query the snapshot comparison results that the test agent does
    url = "http://localhost:${testAgentMappedPort}/test/snapshot"
    urlBuilder = HttpUrl.parse(url).newBuilder()
      .addEncodedQueryParameter("ignores", String.join(",", ignoredKeys) )
      .addQueryParameter("dir", "/dd-smoke-tests/${projectName()}/snapshots")
      .addQueryParameter("token", testTokenID)

    request = new Request.Builder().url(urlBuilder.build()).get().build()
    response = client.newCall(request).execute()

    assert response != null
    println response.body().string()
    assert response.code() == 200
  }

  //prints the log snapshot to the build/reports directory of the specific project
  def printLogToFile(String fileName) {
    File logFile = new File("${buildDirectory}/reports/${fileName}.log")
    if (!logFile.exists()) {
      logFile.createNewFile()
    }
    logFile.write(testAgent.getLogs())
  }

  def cleanupSpec() {
    if (testAgent != null) {
      testAgent.stop()
    }
  }

  // this value needs to be specified in the tests implementation for the test agent to find the correct folder for snapshots
  abstract String projectName()
}
