package datadog.smoketest.appsec

import okhttp3.Request
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit

class SpringBootSmokeTest extends AbstractAppSecServerSmokeTest {

  // Timeout for individual requests
  public static final int REQUEST_TIMEOUT = 5

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.appsec.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(defaultAppSecProperties)
    command.addAll((String[]) ["-jar", springBootShadowJar, "--server.port=${httpPort}"])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  def "malicious WAF request #n th time"() {
    setup:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder()
      .url(url)
      . addHeader("User-Agent", "Arachni/v1")
      .build()

    when:
    def response = client.newCall(request).execute()
    def conditions = new PollingConditions()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains("Sup AppSec Dawg")
    response.body().contentType().toString().contains("text/plain")
    response.code() == 200

    conditions.eventually {
      // Expect reported WAF attack event
      appSecEvents.poll(REQUEST_TIMEOUT, TimeUnit.SECONDS)?.get("event_type") == "security_scanner"
    }

    where:
    n << (1..200)
  }
}
