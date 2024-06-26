package datadog.smoketest.appsec

import datadog.trace.agent.test.utils.ThreadUtils
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import spock.lang.Shared

class SpringBootSmokeTest extends AbstractAppSecServerSmokeTest {

  @Shared
  String buildDir = new File(System.getProperty("datadog.smoketest.builddir")).absolutePath
  @Shared
  String customRulesPath = "${buildDir}/appsec_custom_rules.json"

  @Override
  def logLevel() {
    'DEBUG'
  }

  def prepareCustomRules() {
    // Prepare ruleset with additional test rules
    appendRules(
      customRulesPath,
      [
        [
          id          : '__test_request_body_block',
          name        : 'test rule to block on request body',
          tags        : [
            type      : 'test',
            category  : 'test',
            confidence: '1',
          ],
          conditions  : [
            [
              parameters: [
                inputs: [[address: 'server.request.body']],
                regex : 'dd-test-request-body-block',
              ],
              operator  : 'match_regex',
            ]
          ],
          transformers: [],
          on_match    : ['block']
        ],
        [
          id          : '__test_sqli_stacktrace',
          name        : 'test rule to generate stacktrace on sqli',
          tags        : [
            type      : 'test',
            category  : 'test',
            confidence: '1',
          ],
          conditions: [
            [
              parameters: [
                resource: [[address: "server.db.statement"]],
                params: [[ address: "server.request.query" ]],
                db_type: [[ address: "server.db.system" ]],
              ],
              operator: "sqli_detector",
            ],
          ],
          transformers: [],
          on_match    : ['stack_trace']
        ]
      ])
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    // We run this here to ensure it runs before starting the process. Child setupSpec runs after parent setupSpec,
    // so it is not a valid location.
    prepareCustomRules()

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
      .addHeader("X-Forwarded", 'for="[::ffff:1.2.3.4]"')
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
      assert it['rule']['tags']['type'] == 'attack_tool'
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
      .post(RequestBody.create(MediaType.get('application/json'), '{"v":"/.htaccess"}'  ))
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()
    waitForTraceCount 1

    then:
    responseBodyStr == '/.htaccess'
    response.code() == 200
    forEachRootSpanTrigger {
      assert it['rule']['tags']['type'] == 'lfi'
    }
  }

  def 'block request based on header'() {
    when:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder()
      .url(url)
      .addHeader("User-Agent", "dd-test-scanner-log-block")
      .addHeader("X-Forwarded-For", '80.80.80.80"')
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()

    then:
    responseBodyStr.contains("blocked")
    response.code() == 403

    when:
    waitForTraceCount(1) == 1

    then:
    rootSpans.size() == 1
    forEachRootSpanTrigger {
      assert it['rule']['tags']['type'] == 'attack_tool'
    }
  }

  void 'block request with post parameters'() {
    when:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder()
      .url(url)
      .post(RequestBody.create(MediaType.parse('application/x-www-form-urlencoded'), 'd=dd-test-request-body-block'))
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()

    then:
    responseBodyStr.contains("blocked")
    response.code() == 403

    when:
    waitForTraceCount(1) == 1

    then:
    rootSpans.size() == 1
    forEachRootSpanTrigger {
      assert it['rule']['id'] == '__test_request_body_block'
    }
    rootSpans.each {
      assert it.meta.get('appsec.blocked') != null, 'appsec.blocked is not set'
    }
  }

  void 'rasp reports stacktrace on sql injection'() {
    when:
    String url = "http://localhost:${httpPort}/sqli?id=' OR 1=1 --"
    def request = new Request.Builder()
      .url(url)
      .get()
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()

    then:
    response.code() == 200
    responseBodyStr == 'EXECUTED'

    when:
    waitForTraceCount(1)

    then:
    rootSpans.each {
      assert it.meta.get('appsec.blocked') == null, 'appsec.blocked is set'
      assert it.meta.get('_dd.appsec.json') != null, '_dd.appsec.json is not set'
    }
    def trigger = rootSpans[0].triggers.find { it['rule']['id'] == '__test_sqli_stacktrace' }
    assert trigger != null, 'test trigger not found'
  }
}
