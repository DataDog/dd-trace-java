package datadog.smoketest.appsec

import datadog.appsec.api.blocking.BlockingException
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.ThreadUtils
import groovy.json.JsonSlurper
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import spock.lang.Shared

import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

class SpringBootSmokeTest extends AbstractAppSecServerSmokeTest {

  @Override
  def logLevel() {
    'DEBUG'
  }

  @Shared
  String buildDir = new File(System.getProperty("datadog.smoketest.builddir")).absolutePath
  @Shared
  String customRulesPath = "${buildDir}/appsec_custom_rules.json"

  def prepareCustomRules() {
    // Prepare ruleset with additional test rules
    mergeRules(
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
          id          : '__test_sqli_stacktrace_on_query',
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
        ],
        [
          id          : '__test_sqli_block_on_header',
          name        : 'test rule to block on sqli',
          tags        : [
            type      : 'test',
            category  : 'test',
            confidence: '1',
          ],
          conditions: [
            [
              parameters: [
                resource: [[address: "server.db.statement"]],
                params: [[ address: "server.request.headers.no_cookies" ]],
                db_type: [[ address: "server.db.system" ]],
              ],
              operator: "sqli_detector",
            ],
          ],
          transformers: [],
          on_match    : ['block']
        ],
        [
          id          : '__test_ssrf_block',
          name        : 'Server-side request forgery exploit',
          enable      : 'true',
          tags        : [
            type      : 'ssrf',
            category  : 'vulnerability_trigger',
            cwe       : '918',
            capec     : '1000/225/115/664',
            confidence: '0',
            module    : 'rasp'
          ],
          conditions  : [
            [
              parameters: [
                resource: [[address: 'server.io.net.url']],
                params  : [[address: 'server.request.query']],
              ],
              operator  : "ssrf_detector",
            ],
          ],
          transformers: [],
          on_match    : ['block']
        ],
        [
          id          : 'rasp-930-100',     // to replace default rule
          name        : 'Local File Inclusion  exploit',
          enable      : 'true',
          tags        : [
            type      : 'lfi',
            category  : 'vulnerability_trigger',
            cwe       : '98',
            capec     : '252',
            confidence: '0',
            module    : 'rasp'
          ],
          conditions  : [
            [
              parameters: [
                resource: [[address: 'server.io.fs.file']],
                params  : [[address: 'server.request.query']],
              ],
              operator  : "lfi_detector",
            ],
          ],
          transformers: [],
          on_match    : ['block']
        ],
        [
          id          : '__test_session_id_block',
          name        : 'test rule to block on any session id',
          tags        : [
            type      : 'test',
            category  : 'test',
            confidence: '1',
          ],
          conditions  : [
            [
              parameters: [
                inputs: [[address: 'usr.session_id']],
                regex : '[a-zA-Z0-9]+',
              ],
              operator  : 'match_regex',
            ]
          ],
          transformers: [],
          on_match    : ['block']
        ],
        [
          id          : 'rasp-932-110',  // to replace default rule
          name        : 'Command injection exploit',
          enable      : 'true',
          tags        : [
            type: 'command_injection',
            category: 'vulnerability_trigger',
            cwe: '77',
            capec: '1000/152/248/88',
            confidence: '0',
            module: 'rasp'
          ],
          conditions  : [
            [
              parameters: [
                resource: [[address: 'server.sys.exec.cmd']],
                params  : [[address: 'server.request.body']],
              ],
              operator  : "cmdi_detector",
            ],
          ],
          transformers: [],
          on_match    : ['block']
        ],
        [
          id          : 'rasp-932-100',  // to replace default rule
          name        : 'Shell command injection exploit',
          enable      : 'true',
          tags        : [
            type: 'command_injection',
            category: 'vulnerability_trigger',
            cwe: '77',
            capec: '1000/152/248/88',
            confidence: '0',
            module: 'rasp'
          ],
          conditions  : [
            [
              parameters: [
                resource: [[address: 'server.sys.shell.cmd']],
                params  : [[address: 'server.request.body']],
              ],
              operator  : "shi_detector",
            ],
          ],
          transformers: [],
          on_match    : ['block']
        ],
        [
          id  : "apiA-100-001",
          name: "API 10 tag rule on request headers",
          tags: [
            type    : "api10 request headers",
            category: "attack_attempt"
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address : "server.io.net.request.headers",
                    key_path: ["Witness"]
                  ]
                ],
                list: ["pwq3ojtropiw3hjtowir"]
              ],
              operator: "exact_match"
            ]
          ],
          output: [
            event: true,
            keep : true,
            attributes: [
              "_dd.appsec.trace.req_headers": [
                value: "TAG_API10_REQ_HEADERS"
              ]
            ]
          ],
          on_match: []
        ],
        [
          id  : "apiA-100-002",
          name: "API 10 tag rule on request body",
          tags: [
            type    : "api10 request body",
            category: "attack_attempt"
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address : "server.io.net.request.body",
                    key_path: ["payload_in"]
                  ]
                ],
                list: ["qw2jedrkjerbgol23ewpfirj2qw3or"]
              ],
              operator: "exact_match"
            ]
          ],
          output: [
            event: true,
            keep : true,
            attributes: [
              "_dd.appsec.trace.req_body": [
                value: "TAG_API10_REQ_BODY"
              ]
            ]
          ],
          on_match: []
        ],
        [
          id  : "apiA-100-003",
          name: "API 10 tag rule on request method",
          tags: [
            type    : "api10 request method",
            category: "attack_attempt"
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: "server.io.net.request.method"
                  ]
                ],
                list: ["PUT"]
              ],
              operator: "exact_match"
            ]
          ],
          output: [
            event: true,
            keep : true,
            attributes: [
              "_dd.appsec.trace.req_method": [
                value: "TAG_API10_REQ_METHOD"
              ]
            ]
          ],
          on_match: []
        ],
        [
          id  : "apiA-100-004",
          name: "API 10 tag rule on response status",
          tags: [
            type    : "api10 response status",
            category: "attack_attempt"
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: "server.io.net.response.status"
                  ]
                ],
                list: ["201"]
              ],
              operator: "exact_match"
            ]
          ],
          output: [
            event: true,
            keep : true,
            attributes: [
              "_dd.appsec.trace.res_status": [
                value: "TAG_API10_RES_STATUS"
              ]
            ]
          ],
          on_match: []
        ],
        [
          id  : "apiA-100-005",
          name: "API 10 tag rule on response headers",
          tags: [
            type    : "api10 response headers",
            category: "attack_attempt"
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address : "server.io.net.response.headers",
                    key_path: ["echo-headers"]
                  ]
                ],
                list: ["qwoierj12l3"]
              ],
              operator: "exact_match"
            ]
          ],
          output: [
            event: true,
            keep : true,
            attributes: [
              "_dd.appsec.trace.res_headers": [
                value: "TAG_API10_RES_HEADERS"
              ]
            ]
          ],
          on_match: []
        ],
        [
          id  : "apiA-100-006",
          name: "API 10 tag rule on response body",
          tags: [
            type    : "api10 reponse body",
            category: "attack_attempt"
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: "server.io.net.response.body"
                  ]
                ],
                list: ["kqehf09123r4lnksef"]
              ],
              operator: "exact_match"
            ]
          ],
          output: [
            event: true,
            keep : true,
            attributes: [
              "_dd.appsec.trace.res_body": [
                value: "TAG_API10_RES_BODY"
              ]
            ]
          ],
          on_match: []
        ],
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
      .addHeader("X-Client-Ip", '::ffff:1.2.3.4')
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
    String url = "http://localhost:${httpPort}/sqli/query?id=' OR 1=1 --"
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
    def rootSpans = this.rootSpans.toList()
    rootSpans.size() == 1
    def rootSpan = rootSpans[0]
    assert rootSpan.meta.get('appsec.blocked') == null, 'appsec.blocked is set'
    assert rootSpan.meta.get('_dd.appsec.json') != null, '_dd.appsec.json is not set'
    def trigger
    for (t in rootSpan.triggers) {
      if (t['rule']['id'] == '__test_sqli_stacktrace_on_query') {
        trigger = t
        break
      }
    }
    assert trigger != null, 'test trigger not found'
    rootSpan.span.metaStruct != null
    def stack = rootSpan.span.metaStruct.get('_dd.stack')
    assert stack != null, 'stack is not set'
    def exploit = stack.get('exploit')
    assert exploit != null, 'exploit is not set'
  }

  void 'rasp blocks on sql injection'() {
    when:
    String url = "http://localhost:${httpPort}/sqli/header"
    def request = new Request.Builder()
      .url(url)
      .header("x-custom-header", "' OR 1=1 --")
      .get()
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()

    then:
    response.code() == 403
    responseBodyStr == '{"errors":[{"title":"You\'ve been blocked","detail":"Sorry, you cannot access this page. Please contact the customer service team. Security provided by Datadog."}]}\n'

    when:
    waitForTraceCount(1)

    then:
    def rootSpans = this.rootSpans.toList()
    rootSpans.size() == 1
    def rootSpan = rootSpans[0]
    assert rootSpan.meta.get('appsec.blocked') == 'true', 'appsec.blocked is not set'
    assert rootSpan.meta.get('_dd.appsec.json') != null, '_dd.appsec.json is not set'
    def trigger = null
    for (t in rootSpan.triggers) {
      if (t['rule']['id'] == '__test_sqli_block_on_header') {
        trigger = t
        break
      }
    }
    assert trigger != null, 'test trigger not found'
  }

  void 'rasp blocks on SSRF'() {
    when:
    String url = "http://localhost:${httpPort}/ssrf/${variant}?domain=169.254.169.254"
    def request = new Request.Builder()
      .url(url)
      .get()
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()

    then:
    response.code() == 403
    responseBodyStr.contains('You\'ve been blocked')

    when:
    waitForTraceCount(1)

    then:
    def rootSpans = this.rootSpans.toList()
    rootSpans.size() == 1
    def rootSpan = rootSpans[0]
    assert rootSpan.meta.get('error.message').contains(BlockingException.name) // ensure the block was propagated
    assert rootSpan.meta.get('appsec.blocked') == 'true', 'appsec.blocked is not set'
    assert rootSpan.meta.get('_dd.appsec.json') != null, '_dd.appsec.json is not set'
    def trigger = null
    for (t in rootSpan.triggers) {
      if (t['rule']['id'] == '__test_ssrf_block') {
        trigger = t
        break
      }
    }
    assert trigger != null, 'test trigger not found'
    rootSpan.span.metaStruct == null

    where:
    variant               | _
    'query'               | _
    'okHttp3'             | _
    'okHttp2'             | _
    'apache-httpclient4'  | _
    'commons-httpclient2' | _
  }

  void 'rasp blocks on LFI for #variant'() {
    when:
    String url = "http://localhost:${httpPort}/lfi/"+variant+"?path=." + URLEncoder.encode("../../../etc/passwd", StandardCharsets.UTF_8.name())
    def request = new Request.Builder()
      .url(url)
      .get()
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()

    then:
    response.code() == 403
    responseBodyStr.contains('You\'ve been blocked')

    when:
    waitForTraceCount(1)

    then:
    def rootSpan = findFirstMatchingSpan(variant)
    assert rootSpan.meta.get('appsec.blocked') == 'true', 'appsec.blocked is not set'
    assert rootSpan.meta.get('_dd.appsec.json') != null, '_dd.appsec.json is not set'
    def trigger = null
    for (t in rootSpan.triggers) {
      if (t['rule']['id'] == 'rasp-930-100') {
        trigger = t
        break
      }
    }
    assert trigger != null, 'test trigger not found'
    rootSpan.span.metaStruct == null

    where:
    variant | _
    'paths'    | _
    'file'    | _
    'path'    | _

  }

  def findFirstMatchingSpan(String resource) {
    return this.rootSpans.toList().find { (it.span.resource == 'GET /lfi/' + resource) }
  }


  void 'session id tracking'() {
    given:
    def url = "http://localhost:${httpPort}/session"
    def cookieJar = OkHttpUtils.cookieJar()
    def client = OkHttpUtils.clientBuilder().cookieJar(cookieJar).build()
    def request = new Request.Builder()
      .url(url)
      .get()
      .build()

    when: 'initial request creating the session'
    def firstResponse = client.newCall(request).execute()

    then:
    firstResponse.code() == 200

    when: 'second request with a session'
    def secondResponse = client.newCall(request).execute()

    then:
    waitForTraceCount(2)
    secondResponse.code() == 403
    secondResponse.body().string().contains('You\'ve been blocked')
    final blockedSpans = this.rootSpans.findAll {
      it.meta['_dd.appsec.json'] != null
    }
    assert !blockedSpans.empty, 'appsec.blocked is not set'
    def trigger = null
    for (t in blockedSpans.triggers.flatten()) {
      if (t['rule']['id'] == '__test_session_id_block') {
        trigger = t
        break
      }
    }
    assert trigger != null, 'session_id block trigger not found'
  }

  void 'rasp blocks on CMDI'() {
    when:
    String url = "http://localhost:${httpPort}/cmdi/"+endpoint
    def formBuilder = new FormBody.Builder()
    for (s in cmd) {
      formBuilder.add("cmd", s)
    }
    if (params != null) {
      for (s in params) {
        formBuilder.add("params", s)
      }
    }
    final body = formBuilder.build()
    def request = new Request.Builder()
      .url(url)
      .post(body)
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()

    then:
    response.code() == 403
    responseBodyStr.contains('You\'ve been blocked')

    when:
    waitForTraceCount(1)

    then:
    def rootSpans = this.rootSpans.toList()
    rootSpans.size() == 1
    def rootSpan = rootSpans[0]
    assert rootSpan.meta.get('appsec.blocked') == 'true', 'appsec.blocked is not set'
    assert rootSpan.meta.get('_dd.appsec.json') != null, '_dd.appsec.json is not set'
    def trigger = null
    for (t in rootSpan.triggers) {
      if (t['rule']['id'] == 'rasp-932-110') {
        trigger = t
        break
      }
    }
    assert trigger != null, 'test trigger not found'
    rootSpan.span.metaStruct == null

    where:
    endpoint                    | cmd                              | params
    'arrayCmd'                  | ['/bin/../usr/bin/reboot', '-f'] | null
    'arrayCmdWithParams'        | ['/bin/../usr/bin/reboot', '-f'] | ['param']
    'arrayCmdWithParamsAndFile' | ['/bin/../usr/bin/reboot', '-f'] | ['param']
    'processBuilder'            | ['/bin/../usr/bin/reboot', '-f'] | null
  }

  void 'rasp blocks on SHI'() {
    when:
    String url = "http://localhost:${httpPort}/shi/"+endpoint
    def formBuilder = new FormBody.Builder()
    for (s in cmd) {
      formBuilder.add("cmd", s)
    }
    if (params != null) {
      for (s in params) {
        formBuilder.add("params", s)
      }
    }
    final body = formBuilder.build()
    def request = new Request.Builder()
      .url(url)
      .post(body)
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()

    then:
    response.code() == 403
    responseBodyStr.contains('You\'ve been blocked')

    when:
    waitForTraceCount(1)

    then:
    def rootSpans = this.rootSpans.toList()
    rootSpans.size() == 1
    def rootSpan = rootSpans[0]
    assert rootSpan.meta.get('appsec.blocked') == 'true', 'appsec.blocked is not set'
    assert rootSpan.meta.get('_dd.appsec.json') != null, '_dd.appsec.json is not set'
    def trigger = null
    for (t in rootSpan.triggers) {
      if (t['rule']['id'] == 'rasp-932-100') {
        trigger = t
        break
      }
    }
    assert trigger != null, 'test trigger not found'
    rootSpan.span.metaStruct == null

    where:
    endpoint           | cmd                                  | params
    'cmd'              | ['$(cat /etc/passwd 1>&2 ; echo .)'] | null
    'cmdWithParams'    | ['$(cat /etc/passwd 1>&2 ; echo .)'] | ['param']
    'cmdParamsAndFile' | ['$(cat /etc/passwd 1>&2 ; echo .)'] | ['param']
  }

  void 'API Security samples only one request per endpoint'() {
    given:
    def url = "http://localhost:${httpPort}/api_security/sampling/200?test=value"
    def client = OkHttpUtils.clientBuilder().build()
    def request = new Request.Builder()
      .url(url)
      .addHeader('X-My-Header', "value")
      .get()
      .build()

    when:
    List<Response> responses = (1..3).collect {
      client.newCall(request).execute()
    }

    then:
    responses.each {
      assert it.code() == 200
    }
    waitForTraceCount(3)
    def spans = rootSpans.toList().toSorted { it.span.duration }
    spans.size() == 3
    def sampledSpans = spans.findAll { it.meta.keySet().any { it.startsWith('_dd.appsec.s.req.') } }
    sampledSpans.size() == 1
    def span = sampledSpans[0]
    span.meta.containsKey('_dd.appsec.s.req.query')
    span.meta.containsKey('_dd.appsec.s.req.params')
    span.meta.containsKey('_dd.appsec.s.req.headers')
  }

  void 'API Security request body with json node extraction'() {
    given:
    def url = "http://localhost:${httpPort}/api_security/jackson"
    def client = OkHttpUtils.clientBuilder().build()
    def request = new Request.Builder()
      .url(url)
      .post(RequestBody.create(MediaType.get("application/json"), '{"letters": ["a", "b", "c"]}'))
      .build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.code() == 200
    waitForTraceCount(1)
    def span = rootSpans.first()
    def body = span.meta['_dd.appsec.s.req.body']
    body != null
    final schema = new JsonSlurper().parse(unzip(body))[0]
    assert schema instanceof Map
    assert schema['letters'][1]["len"] == 3
  }

  void 'API Security downstream request header analysis'() {
    when:
    final url = "http://localhost:${httpPort}/api_security/http_client/${variant}"
    final request = new Request.Builder()
      .url(url)
      .get()
      .header('Witness', "pwq3ojtropiw3hjtowir")
      .build()
    final response = client.newCall(request).execute()

    then:
    response.code() == 200
    final span = assertDownstreamTrace()
    span.meta['_dd.appsec.trace.req_headers'] == 'TAG_API10_REQ_HEADERS'

    where:
    variant << httpClientDownstreamAnalysisVariants()
  }

  void 'API Security downstream request body analysis'() {
    when:
    final url = "http://localhost:${httpPort}/api_security/http_client/${variant}"
    final request = new Request.Builder()
      .url(url)
      .post(RequestBody.create(MediaType.parse('application/json'), '{"payload_in": "qw2jedrkjerbgol23ewpfirj2qw3or"}'))
      .build()
    final response = client.newCall(request).execute()

    then:
    response.code() == 200
    final span = assertDownstreamTrace()
    span.meta['_dd.appsec.trace.req_body'] == 'TAG_API10_REQ_BODY'

    where:
    variant << httpClientDownstreamAnalysisVariants()
  }

  void 'API Security downstream request method analysis'() {
    when:
    final url = "http://localhost:${httpPort}/api_security/http_client/${variant}"
    final request = new Request.Builder()
      .url(url)
      .method("PUT", RequestBody.create(MediaType.parse("text/plain"), "hello".bytes))
      .build()
    final response = client.newCall(request).execute()

    then:
    response.code() == 200
    final span = assertDownstreamTrace()
    span.meta['_dd.appsec.trace.req_method'] == 'TAG_API10_REQ_METHOD'

    where:
    variant << httpClientDownstreamAnalysisVariants()
  }

  void 'API Security downstream response status analysis'() {
    when:
    final url = "http://localhost:${httpPort}/api_security/http_client/${variant}"
    final request = new Request.Builder()
      .url(url)
      .get()
      .header('Status', "201")
      .build()
    final response = client.newCall(request).execute()

    then:
    response.code() == 200
    final span = assertDownstreamTrace()
    span.meta['_dd.appsec.trace.res_status'] == 'TAG_API10_RES_STATUS'

    where:
    variant << httpClientDownstreamAnalysisVariants()
  }

  void 'API Security downstream response header analysis'() {
    when:
    final url = "http://localhost:${httpPort}/api_security/http_client/${variant}"
    final request = new Request.Builder()
      .url(url)
      .get()
      .header('echo-headers', "qwoierj12l3")
      .build()
    final response = client.newCall(request).execute()

    then:
    response.code() == 200
    final span = assertDownstreamTrace()
    span.meta['_dd.appsec.trace.res_headers'] == 'TAG_API10_RES_HEADERS'

    where:
    variant << httpClientDownstreamAnalysisVariants()
  }

  void 'API Security downstream response body analysis'() {
    when:
    final url = "http://localhost:${httpPort}/api_security/http_client/${variant}"
    final request = new Request.Builder()
      .url(url)
      .post(RequestBody.create(MediaType.parse('application/json'), '{"payload_out": "kqehf09123r4lnksef"}'))
      .build()
    final response = client.newCall(request).execute()

    then:
    response.code() == 200
    final span = assertDownstreamTrace()
    span.meta['_dd.appsec.trace.res_body'] == 'TAG_API10_RES_BODY'

    where:
    variant << httpClientDownstreamAnalysisVariants()
  }

  private RootSpan assertDownstreamTrace() {
    waitForTraceCount(2) // original + echo

    final rootSpans = this.rootSpans.toList()
    final span = rootSpans.find { it.getSpan().resource.contains('/api_security/http_client') }
    span.metrics['_dd.appsec.downstream_request'] == 1

    return span
  }

  private static List<String> httpClientDownstreamAnalysisVariants() {
    return ['okHttp2', 'okHttp3']
  }

  private static byte[] unzip(final String text) {
    final inflaterStream = new GZIPInputStream(new ByteArrayInputStream(text.decodeBase64()))
    return inflaterStream.getBytes()
  }

}
