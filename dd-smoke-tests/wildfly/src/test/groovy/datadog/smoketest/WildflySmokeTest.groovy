package datadog.smoketest

import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.PortUtils
import okhttp3.Request
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.util.concurrent.atomic.AtomicInteger

class WildflySmokeTest extends AbstractServerSmokeTest {

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
      "-Djboss.http.port=${httpPort}",
      "-Djboss.https.port=${httpsPort}",
      "-Djboss.management.http.port=${managementPort}",
      "-Ddd.trace.experimental.jee.split-by-deployment=true",
      "-Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()}:includeService,DDAgentWriter",
    ]
    processBuilder.environment().put("JAVA_OPTS", javaOpts.collect({ it.replace(' ', '\\ ') }).join(' '))
    return processBuilder
  }

  @Override
  File createTemporaryFile() {
    def ret = File.createTempFile("trace-structure-docs", "out")
    ret
  }

  @Override
  def inferServiceName() {
    // do not set DD_SERVICE
    false
  }

  @Override
  protected boolean isAcceptable(int processIndex, Map<String, AtomicInteger> traceCounts) {
    def hasServletRequestTraces = traceCounts.find { it.getKey() == "[war:servlet.request[war:spring.handler]]" }?.getValue()?.get() == 201
    def hasScheduledEjbTrace = traceCounts.find { it.getKey() == "[war:trace.annotation]" }?.getValue()?.get() == 1
    assert hasScheduledEjbTrace && hasServletRequestTraces: "Encountered traces: " + traceCounts
    return true
  }


  def setupSpec() {
    //wait for the deployment
    new PollingConditions(timeout: 300, delay: 2).eventually {
      assert OkHttpUtils.client().newCall(new Request.Builder().url("http://localhost:$httpPort/war/hello").build()).execute().code() == 200
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

  def "spring controller #n th time"() {
    setup:
    String url = "http://localhost:$httpPort/war/hello"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contentEquals("hello world")
    response.code() == 200
    where:
    n << (1..200)
  }

  def "scheduled ejb has right service name"() {
    setup:
    String url = "http://localhost:$httpPort/war/enableScheduling"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
  }
}
