package datadog.smoketest.rum.tomcat9

import datadog.smoketest.rum.AbstractRumServerSmokeTest
import datadog.trace.api.Platform
import okhttp3.Request
import okhttp3.Response

class Tomcat9RumSmokeTest extends AbstractRumServerSmokeTest {


  @Override
  ProcessBuilder createProcessBuilder() {
    String jarPath = System.getProperty('datadog.smoketest.rum.tomcat9.shadowJar.path')

    List<String> command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(defaultRumProperties)
    if (Platform.isJavaVersionAtLeast(17)) {
      command.addAll((String[]) ['--add-opens', 'java.base/java.lang=ALL-UNNAMED'])
    }
    command.addAll(['-jar', jarPath, Integer.toString(httpPort)])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }

  void 'test RUM SDK injection'() {
    given:
    def url = "http://localhost:${httpPort}/hello"
    def request = new Request.Builder()
      .url(url)
      .get()
      .build()

    when:
    Response response = client.newCall(request).execute()

    then:
    response.code() == 200
    assertRumInjected(response)
  }
}
