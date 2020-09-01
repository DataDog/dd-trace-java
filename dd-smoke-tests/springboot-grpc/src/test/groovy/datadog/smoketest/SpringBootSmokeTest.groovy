package datadog.smoketest

import okhttp3.Request

class SpringBootSmokeTest extends AbstractServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot-grpc.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) ["-jar", springBootShadowJar, "--server.port=${httpPort}", "-Ddd.writer.type=LoggingWriter"])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  def "greeter #n th time"() {
    setup:
    String url = "http://localhost:${httpPort}/${route}"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains("bye")
    response.body().contentType().toString().contains("text/plain")
    response.code() == 200

    where:
    [n, route] << GroovyCollections.combinations((1..200), ["greeting", "async_greeting"])
  }


}
