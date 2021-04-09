package datadog.smoketest

import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.PortUtils
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import spock.lang.Shared

import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import static datadog.trace.test.util.ForkedTestUtils.getMaxMemoryArgumentForFork
import static datadog.trace.test.util.ForkedTestUtils.getMinMemoryArgumentForFork

abstract class AbstractTestAgentSmokeTest extends ProcessManager {

  @Shared
  protected String snapshotDirectory = "${workingDirectory}/snapshots"

  @Shared
  protected String testAgentPath = System.getProperty("datadog.smoketest.test.agent.dir")

  @Shared
  protected int testAgentPort = 8126

  @Shared
  int httpPort = PortUtils.randomOpenPort()

  @Shared
  String testAgentLogFilePath = "${buildDirectory}/reports/testAgent.${this.getClass().getName()}.log"

  @Shared
  String[] defaultJavaProperties = [
    "${getMaxMemoryArgumentForFork()}",
    "${getMinMemoryArgumentForFork()}",
    "-javaagent:${shadowJarPath}",
    "-XX:ErrorFile=/tmp/hs_err_pid%p.log",
    "-Ddd.trace.agent.port=${testAgentPort}",
    "-Ddd.service.name=${SERVICE_NAME}",
    "-Ddd.env=${ENV}",
    "-Ddd.version=${VERSION}",
    "-Ddd.profiling.enabled=false",
    "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
    "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug"
  ]

  @Shared
  protected Process testAgent

  @Shared
  protected OkHttpClient client = OkHttpUtils.client()

  def setupSpec() {
    if (testAgentPath == null) {
      throw new AssertionError("Expected system property for path for Test Agent but found none.")
    }
    assert Files.isRegularFile(Paths.get(testAgentPath))
    List<String> command = new ArrayList<>()
    command.addAll((String[]) [javaPath(), "-jar", testAgentPath])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.redirectErrorStream(true)
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(new File(testAgentLogFilePath)))

    // starts the test agent in port 8126, the test agent will be updated to run on different ports soon
    testAgent = processBuilder.start()
    println testAgent.isAlive()
    PortUtils.waitForPortToOpen(testAgentPort, 20, TimeUnit.SECONDS, testAgent)
    PortUtils.waitForPortToOpen(httpPort, 240, TimeUnit.SECONDS, testedProcess)

    println "Test agent started on port ${testAgentPort}"
    println "Test agent using snapshot directory: ${snapshotDirectory}"
  }

  /**
   * Assert trace structure by sending traces synchronously to the test agent
   * @param testTokenID the key of the test agent. For the test to work, a .snap file of the same name should exist in the /snapshots directory of the project folder
   * @param ignoredKeys list of keys in a trace snapshot that the test agent should ignore
   * @param func the command whose generated trace should be asserted by the testAgent
   */
  def snapshot(String testTokenID, String[] ignoredKeys = ['meta.http.url', 'meta.thread.name', 'meta.peer.port', 'meta.thread.id'], Closure func) {
    //let test agent know the test is started
    def url = "http://localhost:${testAgentPort}/test/start"
    def urlBuilder = HttpUrl.parse(url).newBuilder().addQueryParameter("token", testTokenID)

    def request = new Request.Builder().url(urlBuilder.build()).get().build()
    Response response = client.newCall(request).execute()

    assert response != null
    assert response.code() == 200

    func.call()

    Thread.sleep(1500)

    //resend the test agent results to query the snapshot comparison results that the test agent does
    url = "http://localhost:${testAgentPort}/test/snapshot"
    urlBuilder = HttpUrl.parse(url).newBuilder()
      .addEncodedQueryParameter("ignores", String.join(",", ignoredKeys) )
      .addQueryParameter("dir", "${snapshotDirectory}")
      .addQueryParameter("token", testTokenID)

    request = new Request.Builder().url(urlBuilder.build()).get().build()
    response = client.newCall(request).execute()

    assert response != null
    println response.body().string()
    assert response.code() == 200 : "The trace has failed, see logs for more details"
  }

  def cleanupSpec() {
    testAgent.destroy()
    if (testAgent.isAlive()) {
      testAgent.destroyForcibly()
    }
  }
}
