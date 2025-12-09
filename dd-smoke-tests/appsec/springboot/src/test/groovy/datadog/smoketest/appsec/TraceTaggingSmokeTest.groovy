package datadog.smoketest.appsec

import groovy.json.JsonSlurper
import okhttp3.Request
import spock.lang.Shared

class TraceTaggingSmokeTest extends AbstractAppSecServerSmokeTest {

  @Override
  def logLevel() {
    'DEBUG'
  }

  @Shared
  String buildDir = new File(System.getProperty("datadog.smoketest.builddir")).absolutePath
  @Shared
  String customRulesPath = "${buildDir}/appsec_custom_rules.json"

  def prepareCustomRules() {
    // Create a custom rules file with rules_compat section
    def rulesContent = [
      version: '2.1',
      metadata: [
        rules_version: '1.2.7'
      ],
      rules: [
        [
          id: 'arachni_rule',
          name: 'Arachni',
          tags: [
            type: 'security_scanner',
            category: 'attack_attempt'
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: 'server.request.headers.no_cookies',
                    key_path: ['user-agent']
                  ]
                ],
                regex: '^Arachni\\/v'
              ],
              operator: 'match_regex'
            ]
          ],
          transformers: [],
          on_match: ['block']
        ]
      ],
      rules_compat: [
        [
          id: 'ttr-000-001',
          name: 'Trace Tagging Rule: Attributes, No Keep, No Event',
          tags: [
            type: 'security_scanner',
            category: 'attack_attempt'
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: 'server.request.headers.no_cookies',
                    key_path: ['user-agent']
                  ]
                ],
                regex: '^TraceTagging\\/v1'
              ],
              operator: 'match_regex'
            ]
          ],
          output: [
            event: false,
            keep: false,
            attributes: [
              '_dd.appsec.trace.integer': [
                value: 662607015
              ],
              '_dd.appsec.trace.agent': [
                value: 'TraceTagging/v1'
              ]
            ]
          ],
          on_match: []
        ],
        [
          id: 'ttr-000-002',
          name: 'Trace Tagging Rule: Attributes, Keep, No Event',
          tags: [
            type: 'security_scanner',
            category: 'attack_attempt'
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: 'server.request.headers.no_cookies',
                    key_path: ['user-agent']
                  ]
                ],
                regex: '^TraceTagging\\/v2'
              ],
              operator: 'match_regex'
            ]
          ],
          output: [
            event: false,
            keep: true,
            attributes: [
              '_dd.appsec.trace.integer': [
                value: 602214076
              ],
              '_dd.appsec.trace.agent': [
                value: 'TraceTagging/v2'
              ]
            ]
          ],
          on_match: []
        ],
        [
          id: 'ttr-000-003',
          name: 'Trace Tagging Rule: Attributes, Keep, Event',
          tags: [
            type: 'security_scanner',
            category: 'attack_attempt'
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: 'server.request.headers.no_cookies',
                    key_path: ['user-agent']
                  ]
                ],
                regex: '^TraceTagging\\/v3'
              ],
              operator: 'match_regex'
            ]
          ],
          output: [
            event: true,
            keep: true,
            attributes: [
              '_dd.appsec.trace.integer': [
                value: 299792458
              ],
              '_dd.appsec.trace.agent': [
                value: 'TraceTagging/v3'
              ]
            ]
          ],
          on_match: []
        ],
        [
          id: 'ttr-000-004',
          name: 'Trace Tagging Rule: Attributes, No Keep, Event',
          tags: [
            type: 'security_scanner',
            category: 'attack_attempt'
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: 'server.request.headers.no_cookies',
                    key_path: ['user-agent']
                  ]
                ],
                regex: '^TraceTagging\\/v4'
              ],
              operator: 'match_regex'
            ]
          ],
          output: [
            event: true,
            keep: false,
            attributes: [
              '_dd.appsec.trace.integer': [
                value: 1729
              ],
              '_dd.appsec.trace.agent': [
                address: 'server.request.headers.no_cookies',
                key_path: ['user-agent']
              ]
            ]
          ],
          on_match: []
        ]
      ]
    ]

    // Write the custom rules to file
    def gen = new groovy.json.JsonGenerator.Options().build()
    new File(customRulesPath).withWriter { writer ->
      writer.write(gen.toJson(rulesContent))
    }

    // Add a new property pointing to the new ruleset
    defaultAppSecProperties += "-Ddd.appsec.rules=${customRulesPath}" as String
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

  def "test trace tagging rule with attributes, no keep and no event"() {
    when:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder()
      .url(url)
      .addHeader("User-Agent", "TraceTagging/v1")
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()
    waitForTraceCount(1)

    then:
    responseBodyStr == "Sup AppSec Dawg"
    response.code() == 200
    rootSpans.size() == 1

    def rootSpan = rootSpans[0]
    assert rootSpan.meta['_dd.appsec.trace.agent'] != null, "Missing _dd.appsec.trace.agent from span's meta"
    assert rootSpan.metrics['_dd.appsec.trace.integer'] != null, "Missing _dd.appsec.trace.integer from span's metrics"

    assert rootSpan.meta['_dd.appsec.trace.agent'].startsWith("TraceTagging/v1")
    assert rootSpan.metrics['_dd.appsec.trace.integer'] == 662607015
  }

  def "test trace tagging rule with attributes, keep and no event"() {
    when:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder()
      .url(url)
      .addHeader("User-Agent", "TraceTagging/v2")
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()
    waitForTraceCount(1)

    then:
    responseBodyStr == "Sup AppSec Dawg"
    response.code() == 200
    rootSpans.size() == 1

    def rootSpan = rootSpans[0]
    assert rootSpan.meta['_dd.appsec.trace.agent'] != null, "Missing _dd.appsec.trace.agent from span's meta"
    assert rootSpan.metrics['_dd.appsec.trace.integer'] != null, "Missing _dd.appsec.trace.integer from span's metrics"

    assert rootSpan.meta['_dd.appsec.trace.agent'].startsWith("TraceTagging/v2")
    assert rootSpan.metrics['_dd.appsec.trace.integer'] == 602214076
    assert rootSpan.metrics.get('_sampling_priority_v1') == 2 // USER_KEEP
  }

  def "test trace tagging rule with attributes, keep and event"() {
    when:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder()
      .url(url)
      .addHeader("User-Agent", "TraceTagging/v3")
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()
    waitForTraceCount(1)

    then:
    responseBodyStr == "Sup AppSec Dawg"
    response.code() == 200
    rootSpans.size() == 1

    def rootSpan = rootSpans[0]
    assert rootSpan.meta['_dd.appsec.trace.agent'] != null, "Missing _dd.appsec.trace.agent from span's meta"
    assert rootSpan.metrics['_dd.appsec.trace.integer'] != null, "Missing _dd.appsec.trace.integer from span's metrics"

    assert rootSpan.meta['_dd.appsec.trace.agent'].startsWith("TraceTagging/v3")
    assert rootSpan.metrics['_dd.appsec.trace.integer'] == 299792458
    assert rootSpan.metrics.get('_sampling_priority_v1') == 2 // USER_KEEP

    // Check for WAF attack event
    assert rootSpan.meta['_dd.appsec.json'] != null, "Missing WAF attack event"
    def appsecJson = new JsonSlurper().parseText(rootSpan.meta['_dd.appsec.json'])
    assert appsecJson.triggers != null, "Missing triggers in WAF attack event"
  }

  def "test trace tagging rule with attributes, no keep and event"() {
    when:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder()
      .url(url)
      .addHeader("User-Agent", "TraceTagging/v4")
      .build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()
    waitForTraceCount(1)

    then:
    responseBodyStr == "Sup AppSec Dawg"
    response.code() == 200
    rootSpans.size() == 1

    def rootSpan = rootSpans[0]
    assert rootSpan.meta['_dd.appsec.trace.agent'] != null, "Missing _dd.appsec.trace.agent from span's meta"
    assert rootSpan.metrics['_dd.appsec.trace.integer'] != null, "Missing _dd.appsec.trace.integer from span's metrics"

    assert rootSpan.meta['_dd.appsec.trace.agent'].startsWith("TraceTagging/v4")
    assert rootSpan.metrics['_dd.appsec.trace.integer'] == 1729

    // Check for WAF attack event (should exist since event: true)
    assert rootSpan.meta['_dd.appsec.json'] != null, "Missing WAF attack event"
    def appsecJson = new JsonSlurper().parseText(rootSpan.meta['_dd.appsec.json'])
    assert appsecJson.triggers != null, "Missing triggers in WAF attack event"

    // Should NOT have USER_KEEP sampling priority since keep: false
    assert rootSpan.metrics.get('_sampling_priority_v1') < 2,
    "Should not have USER_KEEP sampling priority when keep: false"
  }
}
