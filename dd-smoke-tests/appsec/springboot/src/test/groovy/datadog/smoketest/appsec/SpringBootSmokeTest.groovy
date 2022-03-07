package datadog.smoketest.appsec

import datadog.trace.agent.test.utils.ThreadUtils
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody

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
    forEachRootSpanTrigger {
      assert it['rule']['tags']['type'] == 'security_scanner'
    }
    rootSpans.each { assert it.meta['actor.ip'] == '1.2.3.4' }
    rootSpans.each {
      assert it.meta['http.response.headers.content-type'] == 'text/plain;charset=UTF-8'
      assert it.meta['http.response.headers.content-length'] == '15'
    }
  }

  def "match server request path params value"() {
    when:
    String url = "http://localhost:${httpPort}/id/appscan_fingerprint"
    def request = new Request.Builder()
      .url(url)
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()
    waitForTraceCount 1

    then:
    responseBodyStr == 'appscan_fingerprint'
    response.code() == 200
    forEachRootSpanTrigger {
      assert it['rule']['tags']['type'] == 'security_scanner'
    }
  }

  void 'stats for the waf are sent'() {
    when:
    String url = "http://localhost:${httpPort}/id/appscan_fingerprint"
    def request = new Request.Builder()
      .url(url)
      .build()
    def response = client.newCall(request).execute()
    waitForTraceCount 1

    then:
    response.code() == 200
    def total = rootSpans[0].span.metrics['_dd.appsec.waf.duration_ext']
    def ddwafRun = rootSpans[0].span.metrics['_dd.appsec.waf.duration']
    total > 0
    ddwafRun > 0
    total >= ddwafRun
  }

  def 'post request with mapped request body'() {
    when:
    String url = "http://localhost:${httpPort}/request-body"
    def request = new Request.Builder()
      .url(url)
      .post(RequestBody.create(MediaType.get('application/json'), '{"v":".htaccess"}'  ))
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()
    waitForTraceCount 1

    then:
    responseBodyStr == '.htaccess'
    response.code() == 200
    forEachRootSpanTrigger {
      assert it['rule']['tags']['type'] == 'lfi'
    }
  }
}
