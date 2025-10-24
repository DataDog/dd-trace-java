package datadog.smoketest

import datadog.trace.test.util.ThreadUtils
import okhttp3.Request
import spock.lang.IgnoreIf
import spock.lang.Shared

@IgnoreIf({
  // TODO https://github.com/eclipse-vertx/vert.x/issues/2172
  new BigDecimal(System.getProperty("java.specification.version")).isAtLeast(17.0) })
class VertxSmokeTest extends AbstractServerSmokeTest {

  @Shared
  int totalInvocations = 1000

  @Override
  ProcessBuilder createProcessBuilder() {
    String vertxUberJar = System.getProperty("datadog.smoketest.vertx.uberJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      "-Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()},DDAgentWriter",
      "-Ddd.app.customlogmanager=true",
      "-Dvertx.http.port=${httpPort}",
      "-jar",
      vertxUberJar
    ])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  File createTemporaryFile() {
    return new File("${buildDirectory}/tmp/trace-structure-vertx.out")
  }

  @Override
  protected Set<String> expectedTraces() {
    return ["[netty.request]"].toSet()
  }

  protected String path() {
    return "/hello"
  }

  def "Test concurrent requests to vertx"() {
    setup:
    def url = "http://localhost:${httpPort}${path()}"
    def request = new Request.Builder().url(url).get().build()

    expect:
    ThreadUtils.runConcurrently(64, totalInvocations, {
      def response = client.newCall(request).execute()

      String body = response.body().string()
      assert body != null
      assert response.body().contentType().toString().contains("text/plain")
      assert response.code() == 200
    })

    waitForTraceCount(totalInvocations) == totalInvocations
  }
}
