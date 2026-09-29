package datadog.smoketest

import datadog.trace.test.agent.decoder.DecodedSpan
import datadog.trace.test.util.ThreadUtils
import okhttp3.Request
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.util.concurrent.ThreadLocalRandom
import java.util.regex.Pattern

abstract class QuarkusNativeSmokeTest extends AbstractServerSmokeTest {

  // Enable trace payload decoding so tests can assert on span tags (e.g. _dd.base_service),
  // not just trace shape.
  @Override
  Closure decodedTracesCallback() {
    return { trace -> true }
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String quarkusNativeExecutable = System.getProperty('datadog.smoketest.quarkus.native.executable')

    List<String> command = new ArrayList<>()
    command.add(quarkusNativeExecutable)
    command.addAll(nativeJavaProperties)
    command.addAll((String[]) [
      "-Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()},DDAgentWriter",
      "-Ddd.app.customlogmanager=true",
      "-Dquarkus.http.port=${httpPort}"
    ])
    command.addAll(additionalArguments())
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  protected List<String> additionalArguments() {
    return Collections.emptyList()
  }

  @Override
  File createTemporaryFile() {
    return new File("${buildDirectory}/tmp/trace-structure-quarkus-native.out")
  }

  @Override
  protected Set<String> expectedTraces() {
    ['[netty.request[vertx.route-handler[jakarta-rs.request]]]'] as Set
  }

  @Shared
  int totalInvocations = 100

  @Shared
  String endpointName = helloEndpointName()

  abstract String helloEndpointName()

  abstract String resourceName()

  @Override
  boolean testTelemetry() {
    return false
  }

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

  def "resolved service does not spuriously tag _dd.base_service"() {
    setup:
    def poll = new PollingConditions(timeout: 30)

    expect:
    // Guards against APMS-20492: on a GraalVM/Mandrel native-image build, the resolved
    // service could get frozen at build time with the native-image builder's own process
    // identity, causing every span to carry a spurious _dd.base_service because it no
    // longer matches the real DD_SERVICE the app is run with. Every span in the trace
    // (not just one) must have the correct service and no _dd.base_service tag.
    doAndValidateRequest(1)
    waitForTrace(poll) { trace ->
      trace.spans.every { DecodedSpan span ->
        span.service == SERVICE_NAME && !span.meta.containsKey('_dd.base_service')
      }
    }
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
