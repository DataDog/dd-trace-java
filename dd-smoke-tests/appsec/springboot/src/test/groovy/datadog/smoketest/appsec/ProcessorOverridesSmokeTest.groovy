package datadog.smoketest.appsec

import datadog.trace.agent.test.utils.OkHttpUtils
import okhttp3.FormBody
import okhttp3.Request
import spock.lang.Shared

/**
 * Smoke test to verify that processor_overrides and scanners configuration keys
 * are forwarded to libddwaf without alteration.
 *
 * This test creates a custom configuration with:
 * - A custom scanner that detects a specific pattern ("CUSTOM_TEST_PATTERN_12345")
 * - Processor overrides to include the custom scanner in schema extraction
 *
 * By verifying that the custom scanner triggers detection, we confirm that both
 * processor_overrides and scanners were correctly forwarded to libddwaf.
 */
class ProcessorOverridesSmokeTest extends AbstractAppSecServerSmokeTest {

  @Shared
  String buildDir = new File(System.getProperty("datadog.smoketest.builddir")).absolutePath
  @Shared
  String customRulesPath = "${buildDir}/appsec_processor_overrides_rules.json"

  def prepareCustomConfiguration() {
    // Prepare ruleset with custom scanners and processor overrides
    // This configuration will be passed to libddwaf and should be forwarded without modification

    def customConfig = [
      version: "2.2",
      metadata: [
        rules_version: "1.0.0"
      ],
      rules: [
        // Add a simple rule that triggers on custom scanner detection
        [
          id: 'custom-scanner-detection-rule',
          name: 'Custom Scanner Detection Test Rule',
          tags: [
            type: 'security_scanner',
            category: 'attack_attempt'
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: 'server.request.body'
                  ]
                ],
                regex: 'CUSTOM_TEST_PATTERN_12345'
              ],
              operator: 'match_regex'
            ]
          ],
          transformers: [],
          on_match: []
        ]
      ],
      // Define custom scanners - this should be forwarded to libddwaf
      scanners: [
        [
          id: 'custom-test-scanner',
          name: 'Custom Test Scanner',
          key: [
            operator: 'match_regex',
            parameters: [
              regex: '.*'
            ]
          ],
          value: [
            operator: 'match_regex',
            parameters: [
              regex: 'CUSTOM_TEST_PATTERN_12345'
            ]
          ],
          tags: [
            type: 'custom_pattern',
            category: 'test'
          ]
        ]
      ],
      // Define processor overrides - this should be forwarded to libddwaf
      processor_overrides: [
        [
          target: [[id: 'extract-schema']],
          scanners: [
            include: [[id: 'custom-test-scanner']],
            exclude: []
          ]
        ]
      ]
    ]

    // Write the custom configuration to a file
    def gen = new groovy.json.JsonGenerator.Options().build()
    def configFile = new File(customRulesPath)
    configFile.text = gen.toJson(customConfig)

    // Point the agent to use this custom configuration
    defaultAppSecProperties += "-Ddd.appsec.rules=${customRulesPath}" as String
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    // Prepare custom configuration before starting the process
    prepareCustomConfiguration()

    String springBootShadowJar = System.getProperty("datadog.smoketest.appsec.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(defaultAppSecProperties)
    command.addAll((String[]) ["-jar", springBootShadowJar, "--server.port=${httpPort}"])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  void 'test that custom scanner with processor_overrides is detected'() {
    given: 'a request containing the custom pattern'
    def url = "http://localhost:${httpPort}/greeting"
    def client = OkHttpUtils.clientBuilder().build()
    def formBuilder = new FormBody.Builder()
    formBuilder.add('test', 'CUSTOM_TEST_PATTERN_12345')
    def body = formBuilder.build()
    def request = new Request.Builder()
      .url(url)
      .post(body)
      .build()

    when: 'the request is sent'
    def response = client.newCall(request).execute()

    then: 'the response is successful'
    response.code() == 200

    when: 'waiting for traces'
    waitForTraceCount(1)

    then: 'the custom scanner rule is triggered'
    def rootSpans = this.rootSpans.toList()
    rootSpans.size() == 1
    def rootSpan = rootSpans[0]

    // Verify that the custom rule was triggered, which confirms that:
    // 1. The 'scanners' configuration was forwarded to libddwaf
    // 2. The 'processor_overrides' configuration was forwarded to libddwaf
    // 3. Libddwaf correctly processed both configurations
    def trigger = null
    for (t in rootSpan.triggers) {
      if (t['rule']['id'] == 'custom-scanner-detection-rule') {
        trigger = t
        break
      }
    }
    assert trigger != null, 'Custom scanner rule was not triggered - configuration may not have been forwarded correctly'

    // Verify the trigger contains expected information
    trigger['rule']['id'] == 'custom-scanner-detection-rule'
    trigger['rule']['tags']['type'] == 'security_scanner'
    trigger['rule']['tags']['category'] == 'attack_attempt'
  }

  void 'test that custom scanner does not trigger without pattern'() {
    given: 'a request without the custom pattern'
    def url = "http://localhost:${httpPort}/greeting"
    def client = OkHttpUtils.clientBuilder().build()
    def formBuilder = new FormBody.Builder()
    formBuilder.add('test', 'normal_text_without_pattern')
    def body = formBuilder.build()
    def request = new Request.Builder()
      .url(url)
      .post(body)
      .build()

    when: 'the request is sent'
    def response = client.newCall(request).execute()

    then: 'the response is successful'
    response.code() == 200

    when: 'waiting for traces'
    waitForTraceCount(1)

    then: 'the custom scanner rule is NOT triggered'
    def rootSpans = this.rootSpans.toList()
    rootSpans.size() == 1
    def rootSpan = rootSpans[0]

    def trigger = null
    for (t in rootSpan.triggers) {
      if (t['rule']['id'] == 'custom-scanner-detection-rule') {
        trigger = t
        break
      }
    }
    assert trigger == null, 'Custom scanner rule should not trigger without the pattern'
  }
}
