package datadog.smoketest.appsec

import okhttp3.Request

class SpringTomcatSmokeTest extends AbstractAppSecServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.appsec.springtomcat7.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.add("-Ddd.iast.enabled=true")
    command.add("-Ddd.iast.stacktrace-leak.suppress=true")
    command.addAll((String[]) ["-jar", springBootShadowJar, "--server.port=${httpPort}"])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  def "suppress exception stacktrace"() {
    when:
    String url = "http://localhost:${httpPort}/exception"
    def request = new Request.Builder()
      .url(url)
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()
    waitForTraceCount 1

    then:
    responseBodyStr.contains('Sorry, you cannot access this page. Please contact the customer service team.')
    response.code() == 500
  }
}