package datadog.smoketest

import datadog.trace.agent.test.utils.ThreadUtils
import okhttp3.FormBody
import okhttp3.Request
import spock.lang.Requires
import spock.lang.Shared

// This test currently fails on IBM JVMs
@Requires({ !System.getProperty("java.vm.name").contains("IBM J9 VM") })
class SpringBootOpenLibertySmokeTest extends AbstractServerSmokeTest {

  @Shared
  int totalInvocations = 100

  @Shared
  String openLibertyShadowJar = System.getProperty("datadog.smoketest.openliberty.jar.path")

  @Override
  ProcessBuilder createProcessBuilder() {
    List<String> command = new ArrayList<>()
    command.add(javaPath())

    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      "-Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()},DDAgentWriter",
      "-Ddd.jmxfetch.enabled=false",
      "-jar",
      openLibertyShadowJar,
      "--server.port=${httpPort}"
    ])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }

  @Override
  File createTemporaryFile() {
    return new File("${buildDirectory}/tmp/springboot-openliberty.out")
  }

  @Override
  protected Set<String> expectedTraces() {
    return [
      "[servlet.request[spring.handler[http.request]]]",
      "[servlet.request[spring.handler]]"
    ].toSet()
  }

  def "Test concurrent requests to Spring Boot running Open Liberty"() {
    setup:
    def url = "http://localhost:${httpPort}/connect"
    def request = new Request.Builder().url(url).get().build()

    expect:
    ThreadUtils.runConcurrently(10, totalInvocations, {
      def response = client.newCall(request).execute()

      assert response.body().string() != null
      assert response.body().contentType().toString().contains("text/plain")
      assert response.code() == 200
    })

    waitForTraceCount(2 * totalInvocations) == 2 * totalInvocations
  }

  def "Test concurrent high load requests to Spring Boot running Open Liberty"() {
    def url = "http://localhost:${httpPort}/connect/0"
    def formBody = new FormBody.Builder()
    def value = "not too big!"
    for (int i = 0; i < 100; i++) {
      formBody.add("test" + i, value)
    }
    def request = new Request.Builder().url(url).post(formBody.build()).build()

    expect:
    ThreadUtils.runConcurrently(10, totalInvocations, {
      def response = client.newCall(request).execute()

      assert response.body().string() != null
      assert response.body().contentType().toString().contains("text/plain")
      assert response.code() == 200
    })

    waitForTraceCount(totalInvocations) ==  totalInvocations
  }
}
