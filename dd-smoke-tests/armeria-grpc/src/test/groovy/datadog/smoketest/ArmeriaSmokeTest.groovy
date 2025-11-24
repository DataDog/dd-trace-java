package datadog.smoketest


import datadog.armeria.grpc.Hello
import datadog.trace.test.util.ThreadUtils
import spock.lang.Shared

import java.util.concurrent.ThreadLocalRandom
import java.util.regex.Pattern

class ArmeriaSmokeTest extends AbstractServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String armeriaUberJar = System.getProperty("datadog.smoketest.armeria.uberJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      "-Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()},DDAgentWriter",
      "-Darmeria.http.port=${httpPort}",
      "-Dcom.linecorp.armeria.verboseResponses=true",
      "-jar",
      armeriaUberJar
    ])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  File createTemporaryFile() {
    return new File("${buildDirectory}/tmp/trace-structure-armeria.out")
  }

  @Override
  protected Set<String> expectedTraces() {
    return [].toSet()
  }

  @Shared
  int totalInvocations = 100

  @Shared
  String endpointName = ""

  @Shared
  ArmeriaGrpcClient armeriaGrpcClient = new ArmeriaGrpcClient("localhost", httpPort)

  def "get welcome endpoint in parallel"() {
    expect:
    // Do one request before to initialize the server
    doAndValidateRequest(1)
    ThreadUtils.runConcurrently(10, totalInvocations - 1, {
      def id = ThreadLocalRandom.current().nextInt(1, 4711)
      doAndValidateRequest(id)
    })
    waitForTraceCount(totalInvocations) >= totalInvocations
    validateLogInjection() == totalInvocations
  }

  void doAndValidateRequest(int id) {
    Hello.HelloReply response = armeriaGrpcClient.hello(id)
    assert response.getMessage() == "Hello $id!"
  }

  int validateLogInjection() {
    BufferedReader reader = new BufferedReader(new FileReader(new File(logFilePath)))
    int lines = 0
    try {
      String line = reader.readLine()
      while (null != line) {
        if (line.contains("|TT|")) {
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
