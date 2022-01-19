package datadog.smoketest.appsec

import datadog.trace.agent.test.utils.ThreadUtils
import okhttp3.Request

class SpringBootSmokeTest extends AbstractAppSecServerSmokeTest {
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

  void doAndValidateRequest() {
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder()
      .url(url)
      .addHeader("User-Agent", "Arachni/v1")
      .addHeader("Forwarded", 'for="[::ffff:1.2.3.4]"')
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()
    assert responseBodyStr == "Sup AppSec Dawg"
    assert response.code() == 200
  }

  def "malicious WAF request concurrently"() {
    expect:
    // Do one request before to initialize the server
    doAndValidateRequest()
    ThreadUtils.runConcurrently(10, 199, {
      doAndValidateRequest()
    })
    waitForTraceCount(200) == 200
    rootSpans.size() == 200
    rootSpans.collectMany {it.triggers }.forEach {
      assert it['rule']['tags']['type'] == 'security_scanner'
    }
    rootSpans.each { assert it.meta['actor.ip'] == '1.2.3.4' }
  }
}
