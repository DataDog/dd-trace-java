package datadog.smoketest.appsec

import datadog.appsec.api.blocking.BlockingException
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import spock.lang.Shared
import spock.lang.Unroll

/**
 * Reproduces a Dropwizard 5 stack (Jersey 3.1 on Jetty 12.1 ee10, with a newer jetty-server core
 * than the ee10 servlet layer) to verify AppSec blocking end to end, both in the request phase and
 * in the response phase.
 */
class DropwizardSmokeTest extends AbstractAppSecServerSmokeTest {

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
          id          : '__test_nfd_probe_block',
          name        : 'test rule to block on a 404 response for the probe paths',
          tags        : [
            type      : 'test',
            category  : 'test',
            confidence: '1',
          ],
          conditions  : [
            [
              parameters: [
                inputs: [[address: 'server.response.status']],
                regex : '^404$',
              ],
              operator  : 'match_regex',
            ],
            [
              parameters: [
                inputs: [[address: 'server.request.uri.raw']],
                regex : 'nfd-probe',
              ],
              operator  : 'match_regex',
            ]
          ],
          transformers: [],
          on_match    : ['block']
        ],
        [
          id          : '__test_json_body_block',
          name        : 'test rule to block on a JSON request body',
          tags        : [
            type      : 'test',
            category  : 'test',
            confidence: '1',
          ],
          conditions  : [
            [
              parameters: [
                inputs: [[address: 'server.request.body']],
                regex : 'dd-test-json-body-block',
              ],
              operator  : 'match_regex',
            ]
          ],
          transformers: [],
          on_match    : ['block']
        ],
        [
          id          : 'rasp-934-100',  // to replace default rule
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
          id          : '__test_user_block',
          name        : 'test rule to block on a user id',
          tags        : [
            type      : 'test',
            category  : 'test',
            confidence: '1',
          ],
          conditions  : [
            [
              parameters: [
                inputs: [[address: 'usr.id']],
                regex : '^user-to-block$',
              ],
              operator  : 'match_regex',
            ]
          ],
          transformers: [],
          on_match    : ['block']
        ],
      ])
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    // We run this here to ensure it runs before starting the process. Child setupSpec runs after parent setupSpec,
    // so it is not a valid location.
    prepareCustomRules()

    String dropwizardShadowJar = System.getProperty("datadog.smoketest.appsec.dropwizard5.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(defaultAppSecProperties)
    command.addAll((String[]) ["-Ddw.server.connector.port=${httpPort}", "-jar", dropwizardShadowJar])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  void 'request with no attack is not blocked'() {
    when:
    def response = get('/api/hello')
    def responseBodyStr = response.body().string()

    then:
    response.code() == 200
    responseBodyStr == 'OK'
    def rootSpan = rootSpanFor('/api/hello')
    rootSpan.meta.get('appsec.blocked') == null
    rootSpan.meta.get('http.status_code') == '200'
  }

  void 'block request based on header'() {
    when:
    def response = client.newCall(new Request.Builder()
      .url("http://localhost:${httpPort}/api/hello?scenario=header")
      .addHeader('User-Agent', 'dd-test-scanner-log-block')
      .get()
      .build()).execute()

    then:
    assertBlocked(response, '/api/hello?scenario=header', 'ua0-600-56x')
  }

  @Unroll
  void 'block on 404 response for #scenario'() {
    when:
    def response = get(path)

    then:
    assertBlocked(response, path, '__test_nfd_probe_block')

    where:
    scenario                        | path
    'resource returning 404 status' | '/api/nfd-probe-status'
    'resource throwing 404'         | '/api/nfd-probe-throw'
    'no matching Jersey resource'   | '/api/nfd-probe-nomatch'
    'path outside Jersey servlet'   | '/nfd-probe-outside'
  }

  void 'block request based on JSON body'() {
    when:
    def response = client.newCall(new Request.Builder()
      .url("http://localhost:${httpPort}/api/json")
      .post(RequestBody.create(MediaType.get('application/json'), '{"key":"dd-test-json-body-block"}'))
      .build()).execute()

    then:
    assertBlocked(response, '/api/json', '__test_json_body_block')
  }

  void 'rasp blocks on SSRF'() {
    when:
    def response = get('/api/ssrf?domain=169.254.169.254')

    then:
    def rootSpan = assertBlocked(response, '/api/ssrf?domain=169.254.169.254', 'rasp-934-100')
    // ensure the block was propagated
    rootSpan.meta.get('error.type') == BlockingException.name
  }

  void 'block request based on user id'() {
    when:
    def response = get('/api/user?id=user-to-block')

    then:
    assertBlocked(response, '/api/user?id=user-to-block', '__test_user_block')
  }

  private Response get(final String pathAndQuery) {
    client.newCall(new Request.Builder()
      .url("http://localhost:${httpPort}${pathAndQuery}")
      .get()
      .build()).execute()
  }

  /**
   * Waits for the root span of the request to {@code pathAndQuery}. Selecting by URL instead of
   * counting traces avoids picking up a late trace from the previous feature.
   */
  private RootSpan rootSpanFor(final String pathAndQuery) {
    RootSpan rootSpan = null
    defaultPoll.eventually {
      def matching = rootSpans.toList().findAll {
        def url = it.meta.get('http.url')
        if (url == null) {
          return false
        }
        def uri = new URI(url)
        (uri.rawQuery ? "${uri.rawPath}?${uri.rawQuery}" : uri.rawPath) == pathAndQuery
      }
      assert matching.size() == 1, "expected one root span for ${pathAndQuery}"
      rootSpan = matching[0]
    }
    return rootSpan
  }

  /** Asserts the client got the default blocking page and the root span records the block. */
  private RootSpan assertBlocked(final Response response, final String pathAndQuery, final String ruleId) {
    def responseBodyStr = response.body().string()
    assert response.code() == 403
    assert response.header('Content-Type')?.startsWith('application/json')
    assert responseBodyStr.contains('"title":"You\'ve been blocked"')
    assert responseBodyStr.contains('"security_response_id":')

    def rootSpan = rootSpanFor(pathAndQuery)
    assert rootSpan.meta.get('appsec.blocked') == 'true', 'appsec.blocked is not set'
    assert rootSpan.meta.get('http.status_code') == '403'
    assert rootSpan.meta.get('_dd.appsec.json') != null, '_dd.appsec.json is not set'
    def trigger = null
    for (t in rootSpan.triggers) {
      if (t['rule']['id'] == ruleId) {
        trigger = t
        break
      }
    }
    assert trigger != null, "trigger ${ruleId} not found"
    return rootSpan
  }
}
