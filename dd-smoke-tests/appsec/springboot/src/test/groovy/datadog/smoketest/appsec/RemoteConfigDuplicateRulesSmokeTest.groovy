package datadog.smoketest.appsec

import datadog.trace.agent.test.utils.OkHttpUtils
import okhttp3.Request
import spock.lang.Shared

import java.util.concurrent.TimeUnit

/**
 * Smoke test that simulates the real production scenario where:
 * 1. default_config.json is loaded with base rules
 * 2. Remote Config sends an "update" with:
 *    - Rules that ALREADY EXIST (should REPLACE, not duplicate)
 *    - New rules that weren't in default
 * 3. libddwaf detects DUPLICATES and generates warnings
 *
 * In production we saw:
 *   - 159 rules in default_config.json
 *   - 172 duplicate warnings
 *   - 158 rules duplicated (existed in both)
 *   - 14 new rules (only in Remote Config)
 */
class RemoteConfigDuplicateRulesSmokeTest extends AbstractAppSecServerSmokeTest {

  @Override
  def logLevel() {
    'DEBUG'
  }

  @Shared
  String buildDir = new File(System.getProperty("datadog.smoketest.builddir")).absolutePath

  @Shared
  String customRulesPath = "${buildDir}/appsec_remoteconfig_base_rules.json"

  @Shared
  List<String> capturedLogs = []

  @Override
  void beforeProcessBuilders() {
    // Set Remote Config BEFORE creating the process
    prepareRemoteConfigWithDuplicates()
    super.beforeProcessBuilders()
  }

  def prepareRemoteConfigWithDuplicates() {
    // This simulates Remote Config sending updates with same rule IDs
    def remoteConfigRules = [
      // DUPLICATED RULES - Same IDs as in base config
      // In production there were 158 duplicated rules
      [
        id: 'blk-001-001',  // DUPLICATED
        name: 'Block IP Addresses - Updated via Remote Config',
        tags: [
          type: 'block_ip',
          category: 'security_scanner',
          updated: 'true'  // Marker to identify it came from RC
        ],
        conditions: [
          [
            parameters: [
              inputs: [[address: 'http.client_ip']],
              data: 'blocked_ips'
            ],
            operator: 'ip_match'
          ]
        ],
        transformers: ['removeNulls'],
        on_match: ['block']
      ],
      [
        id: 'blk-001-002',  // DUPLICATED
        name: 'Block User Addresses - Updated',
        tags: [type: 'block_user', category: 'security_scanner'],
        conditions: [
          [
            parameters: [
              inputs: [[address: 'usr.id']],
              data: 'blocked_users'
            ],
            operator: 'exact_match'
          ]
        ],
        transformers: [],
        on_match: ['block']
      ],
      [
        id: 'crs-913-110',  // DUPLICATED
        name: 'Acunetix - Updated',
        tags: [type: 'security_scanner', category: 'attack_attempt'],
        conditions: [
          [
            parameters: [
              inputs: [[address: 'server.request.headers.no_cookies']],
              key_path: ['user-agent'],
              regex: 'Acunetix'
            ],
            operator: 'match_regex'
          ]
        ],
        transformers: ['lowercase'],
        on_match: []
      ],
      [
        id: 'crs-921-110',  // DUPLICATED
        name: 'HTTP Request Smuggling Attack - Updated',
        tags: [type: 'http_protocol_violation', category: 'attack_attempt'],
        conditions: [
          [
            parameters: [
              inputs: [[address: 'server.request.headers.no_cookies']],
              key_path: ['content-length'],
              regex: '.*,.*'
            ],
            operator: 'match_regex'
          ]
        ],
        transformers: [],
        on_match: []
      ],
      [
        id: 'crs-942-100',  // DUPLICATED
        name: 'SQL Injection Attack - Updated',
        tags: [type: 'sql_injection', category: 'attack_attempt'],
        conditions: [
          [
            parameters: [
              inputs: [[address: 'server.request.query']]
            ],
            operator: 'is_sqli'
          ]
        ],
        transformers: [],
        on_match: []
      ],
      // NEW RULES - Simulate the 14 rules that only exist in Remote Config
      [
        id: 'api-001-100',  // NEW
        name: 'API Security Rule 100',
        tags: [type: 'api_security', category: 'attack_attempt'],
        conditions: [
          [
            parameters: [
              inputs: [[address: 'server.request.uri.raw']],
              regex: '/api/.*'
            ],
            operator: 'match_regex'
          ]
        ],
        transformers: [],
        on_match: []
      ],
      [
        id: 'api-001-110',  // NEW
        name: 'API Security Rule 110',
        tags: [type: 'api_security', category: 'attack_attempt'],
        conditions: [
          [
            parameters: [
              inputs: [[address: 'server.request.method']],
              list: ['PUT', 'DELETE']
            ],
            operator: 'match'
          ]
        ],
        transformers: [],
        on_match: []
      ]
    ]

    def remoteConfigProcessors = [
      // DUPLICATED PROCESSOR
      [
        id: "http-network-fingerprint",  // DUPLICATED
        generator: "http_network_fingerprint",
        conditions: [
          [
            operator: "exists",
            parameters: [
              inputs: [[address: "waf.context.event"]]
            ]
          ]
        ],
        parameters: [
          mappings: [
            [
              headers: [[address: "server.request.headers.no_cookies"]],
              output: "_dd.appsec.fp.http.network.updated"  // Different output
            ]
          ]
        ],
        evaluate: false,
        output: true
      ]
    ]

    def remoteConfigData = [
      version: "2.2",
      metadata: [
        rules_version: "1.1.0-remoteconfig"
      ],
      rules: remoteConfigRules,
      processors: remoteConfigProcessors
    ]

    // Set Remote Config using the inherited infrastructure
    setRemoteConfig("datadog/2/ASM_DD/config_id/config", groovy.json.JsonOutput.toJson(remoteConfigData))
  }

  def prepareBaseConfiguration() {
    // DON'T provide a custom rules file - let the agent use its default bundled config
    // This way Remote Config will be applied (agent refuses RC when custom rules are provided)

    // Enable Remote Config (parent class disables it by default)
    defaultAppSecProperties += "-Ddd.remote_config.enabled=true" as String
    defaultAppSecProperties += "-Ddd.remote_config.url=http://localhost:${server.address.port}/v0.7/config" as String
    defaultAppSecProperties += "-Ddd.remote_config.poll_interval.seconds=1" as String
    defaultAppSecProperties += "-Ddd.remote_config.integrity_check.enabled=false" as String
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    prepareBaseConfiguration()

    String springBootShadowJar = System.getProperty("datadog.smoketest.appsec.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(defaultAppSecProperties)
    command.addAll((String[]) ["-jar", springBootShadowJar, "--server.port=${httpPort}"])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    processBuilder.redirectErrorStream(true)

    return processBuilder
  }

  void 'test Remote Config applies without duplicate warnings'() {
    when: 'we wait for the agent to start and apply Remote Config'
    // Remote Config was already set in beforeProcessBuilders() BEFORE process started
    // Now we just need to wait for agent to start and apply it
    Thread.sleep(5000)  // Give time for agent to initialize and apply RC

    then: 'we make a request to ensure the app is running'
    def url = "http://localhost:${httpPort}/greeting"
    def client = OkHttpUtils.clientBuilder()
      .readTimeout(30, TimeUnit.SECONDS)
      .build()
    def request = new Request.Builder()
      .url(url)
      .get()
      .build()
    def response = client.newCall(request).execute()

    response.code() == 200

    and: 'Remote Config is applied successfully WITHOUT duplicate warnings'
    Thread.sleep(2000)  // Give time for all logs to be written

    // Read logs from the log file (process output is captured to file by ProcessManager)
    capturedLogs.clear()
    def logFile = new File(logFilePath)
    if (logFile.exists()) {
      capturedLogs.addAll(logFile.readLines())
    }

    // Verify Remote Config was applied
    def remoteConfigApplied = capturedLogs.find {
      it.contains('AppSec loaded') && it.contains('rules from file datadog/2/ASM_DD/config_id/config')
    }

    def defaultConfigRemoved = capturedLogs.find {
      it.contains('Removing default config ASM_DD/default')
    }

    // Check for NO duplicate warnings (proving the fix works)
    def duplicateRuleWarnings = capturedLogs.findAll {
      it.contains('WARN') && it.contains('ddwaf_native') && it.contains('Duplicate rule')
    }
    def duplicateProcessorWarnings = capturedLogs.findAll {
      it.contains('WARN') && it.contains('ddwaf_native') && it.contains('Duplicate processor')
    }

    // Verifications - The fix should work, so NO duplicates expected
    assert defaultConfigRemoved != null, "Default config should be removed before applying Remote Config"
    assert remoteConfigApplied != null, "Remote Config should be applied successfully"
    assert duplicateRuleWarnings.isEmpty(), "Expected NO duplicate rule warnings (fix is working), but found: ${duplicateRuleWarnings.size()}"
    assert duplicateProcessorWarnings.isEmpty(), "Expected NO duplicate processor warnings (fix is working), but found: ${duplicateProcessorWarnings.size()}"
  }
}
