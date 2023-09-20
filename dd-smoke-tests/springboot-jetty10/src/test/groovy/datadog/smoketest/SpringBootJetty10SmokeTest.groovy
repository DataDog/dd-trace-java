package datadog.smoketest

import okhttp3.Request


class SpringBootJetty10SmokeTest extends AbstractServerSmokeTest {

  @Override
  String logLevel() {
    "debug"
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")
    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      "-Ddd.writer.type=TraceStructureWriter:${output.getAbsolutePath()}",
      "-jar",
      springBootShadowJar,
      "--server.port=${httpPort}"
    ])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  protected File createTemporaryFile() {
    return File.createTempFile("trace-structure-SpringBootJetty10SmokeTest", "out")
  }

  @Override
  protected Set<String> expectedTraces() {
    ["[servlet.request[spring.handler]]"]
  }

  def "default home page #n th time"() {
    setup:
    String url = "http://localhost:${httpPort}/hello"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contentEquals("world")
    response.body().contentType().toString().contains("text/plain")
    response.code() == 200

    where:
    n << (1..200)
  }
}
