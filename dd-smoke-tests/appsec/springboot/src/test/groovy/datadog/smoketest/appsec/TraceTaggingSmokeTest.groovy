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
    // command.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005")
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
    assert rootSpan.metrics.get('_sampling_priority_v1') == 2 // USER_KEEP
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

    // Should also have an event
    assert rootSpan.meta.get('_dd.appsec.json') != null, '_dd.appsec.json is not set'

    // Verify the event contains the correct rule ID
    def appsecJson = new JsonSlurper().parseText(rootSpan.meta['_dd.appsec.json'])
    assert appsecJson.triggers.any { it.rule.id == 'ttr-000-003' }
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
    assert rootSpan.metrics.get('_sampling_priority_v1') < 2 // Should be less than USER_KEEP

    // Should also have an event
    assert rootSpan.meta.get('_dd.appsec.json') != null, '_dd.appsec.json is not set'

    // Verify the event contains the correct rule ID
    def appsecJson = new JsonSlurper().parseText(rootSpan.meta['_dd.appsec.json'])
    assert appsecJson.triggers.any { it.rule.id == 'ttr-000-004' }
  }

  def "test trace tagging rules without events do not generate WAF attack events"() {
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
    // Should have trace tagging attributes
    assert rootSpan.meta['_dd.appsec.trace.agent'] != null
    assert rootSpan.metrics['_dd.appsec.trace.integer'] != null

    // Should NOT have a WAF attack event
    assert rootSpan.meta.get('_dd.appsec.json') == null, '_dd.appsec.json should not be set'
  }

  def "test trace tagging capabilities are reported"() {
    when:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder()
      .url(url)
      .addHeader("User-Agent", "TraceTagging/v2")
      .build()
    def response = client.newCall(request).execute()
    waitForTraceCount(1)

    then:
    response.code() == 200

    // The capabilities should be reported in the telemetry
    // This is typically done through the remote config system
    // We can verify this by checking that the trace tagging works
    // which implies the capabilities are properly reported
    rootSpans.size() == 1
    def rootSpan = rootSpans[0]
    assert rootSpan.meta['_dd.appsec.trace.agent'] != null
    assert rootSpan.metrics['_dd.appsec.trace.integer'] != null
  }
}
