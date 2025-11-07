package datadog.smoketest

import datadog.trace.test.util.ThreadUtils
import okhttp3.Request
import spock.lang.Shared

import java.util.concurrent.ThreadLocalRandom
import java.util.regex.Pattern

abstract class QuarkusSmokeTest extends AbstractServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String quarkusUberJar = System.getProperty("datadog.smoketest.quarkus.uberJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      "-Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()},DDAgentWriter",
      "-Ddd.app.customlogmanager=true",
      "-Dquarkus.http.port=${httpPort}",
      "-jar",
      quarkusUberJar
    ])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  File createTemporaryFile() {
    return new File("${buildDirectory}/tmp/trace-structure-quarkus.out")
  }

  @Override
  protected Set<String> expectedTraces() {
    ['[netty.request[vertx.route-handler[jax-rs.request]]]'] as Set
  }

  @Shared
  int totalInvocations = 100

  @Shared
  String endpointName = helloEndpointName()

  abstract String helloEndpointName()

  abstract String resourceName()

  def "get welcome endpoint in parallel"() {
    expect:
    // Do one request before to initialize the server
    doAndValidateRequest(1)
    ThreadUtils.runConcurrently(10, totalInvocations - 1, {
      def id = ThreadLocalRandom.current().nextInt(1, 4711)
      doAndValidateRequest(id)
    })
    waitForTraceCount(totalInvocations) == totalInvocations
    validateLogInjection(resourceName()) == totalInvocations
  }

  void doAndValidateRequest(int id) {
    String url = "http://localhost:$httpPort/$endpointName?id=$id"
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()
    assert responseBodyStr == "Hello $id!"
    assert response.code() == 200
  }

  int validateLogInjection(String resourceName) {
    BufferedReader reader = new BufferedReader(new FileReader(new File(logFilePath)))
    int lines = 0
    try {
      String line = reader.readLine()
      while (null != line) {
        if (line.contains(resourceName)) {
          lines++
          def parts = line.split(Pattern.quote("|"))
          def mdcTraceId = parts[2]
          def mdcSpanId = parts[4]
          def tracerTraceId = parts[6]
          def tracerSpanId = parts[8]
          assert tracerTraceId != ""
          assert mdcTraceId == tracerTraceId
          assert tracerSpanId != ""
          assert mdcSpanId == tracerSpanId
        }
        line = reader.readLine()
      }
    } finally {
      reader.close()
    }
    return lines
  }
}
