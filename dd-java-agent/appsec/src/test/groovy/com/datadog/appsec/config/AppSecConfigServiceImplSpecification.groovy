package com.datadog.appsec.config

import com.datadog.appsec.AppSecSystem
import com.datadog.appsec.util.AbortStartupException
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_EXTENDED_DATA_COLLECTION
import datadog.remoteconfig.ConfigurationChangesTypedListener
import datadog.remoteconfig.ConfigurationDeserializer
import datadog.remoteconfig.ConfigurationEndListener
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.Product
import datadog.remoteconfig.state.ConfigKey
import datadog.remoteconfig.state.ParsedConfigKey
import datadog.remoteconfig.state.ProductListener
import datadog.trace.api.Config
import datadog.trace.api.ProductActivation
import datadog.trace.api.UserIdCollectionMode
import datadog.trace.api.telemetry.WafMetricCollector
import datadog.trace.test.util.DDSpecification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_ACTIVATION
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_AUTO_USER_INSTRUM_MODE
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_CUSTOM_DATA_SCANNERS
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_CUSTOM_RULES
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_DD_MULTICONFIG
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_DD_RULES
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_EXCLUSIONS
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_EXCLUSION_DATA
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_HEADER_FINGERPRINT
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_IP_BLOCKING
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_NETWORK_FINGERPRINT
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_PROCESSOR_OVERRIDES
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_RASP_CMDI
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_RASP_LFI
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_RASP_SHI
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_RASP_SQLI
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_RASP_SSRF
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_REQUEST_BLOCKING
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_SESSION_FINGERPRINT
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_TRACE_TAGGING_RULES
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_TRUSTED_IPS
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_USER_BLOCKING
import static datadog.remoteconfig.Capabilities.CAPABILITY_ENDPOINT_FINGERPRINT
import static datadog.remoteconfig.PollingHinterNoop.NOOP
import static datadog.trace.api.UserIdCollectionMode.ANONYMIZATION
import static datadog.trace.api.UserIdCollectionMode.DISABLED
import static datadog.trace.api.UserIdCollectionMode.IDENTIFICATION

class AppSecConfigServiceImplSpecification extends DDSpecification {
  ConfigurationPoller poller = Mock()
  Config config = Mock(Class.forName('datadog.trace.api.Config')) as Config
  AppSecModuleConfigurer.Reconfiguration reconf = Stub()
  AppSecConfigServiceImpl appSecConfigService
  SavedListeners listeners
  protected static final ORIGINAL_METRIC_COLLECTOR = WafMetricCollector.get()

  void cleanup() {
    appSecConfigService?.close()
  }

  void setup() {
    appSecConfigService = new AppSecConfigServiceImpl(config, poller, reconf)
    listeners = new SavedListeners()
  }

  void 'maybeStartConfigPolling subscribes to the configuration poller'() {
    setup:
    appSecConfigService.init()
    AppSecSystem.active = false
    config.getAppSecActivation() >> ProductActivation.ENABLED_INACTIVE

    when:
    appSecConfigService.maybeSubscribeConfigPolling()

    then:
    0 * poller.addListener(Product.ASM_DD, _)
    1 * poller.addListener(Product.ASM_FEATURES, _, _)
    0 * poller.addListener(Product.ASM, _)
    0 * poller.addListener(Product.ASM_DATA, _)
    1 * poller.addConfigurationEndListener(_)
    1 * poller.addCapabilities(CAPABILITY_ASM_ACTIVATION)
  }

  void 'no subscription to ASM_FEATURES if appsec is fully enabled'() {
    setup:
    appSecConfigService.init()
    AppSecSystem.active = true
    config.getAppSecActivation() >> ProductActivation.FULLY_ENABLED

    when:
    appSecConfigService.maybeSubscribeConfigPolling()

    then:
    1 * poller.addListener(Product.ASM_DD, _)
    1 * poller.addListener(Product.ASM_FEATURES, _, _)
    1 * poller.addListener(Product.ASM, _)
    1 * poller.addListener(Product.ASM_DATA, _)
    1 * poller.addListener(Product.DEBUG, _, _)
    1 * poller.addConfigurationEndListener(_)
    0 * poller.addListener(*_)
    0 * poller.addCapabilities(CAPABILITY_ASM_ACTIVATION)
  }

  void 'no subscription to ASM_FEATURES if appsec is fully disabled'() {
    setup:
    appSecConfigService.init()
    AppSecSystem.active = false
    config.getAppSecActivation() >> ProductActivation.FULLY_DISABLED

    when:
    appSecConfigService.maybeSubscribeConfigPolling()

    then:
    0 * poller.addConfigurationEndListener(_)
    0 * poller.addListener(*_)
    0 * poller.addCapabilities(*_)
  }

  void 'no subscription to ASM ASM_DD ASM_DATA if custom rules are provided'() {
    setup:
    Path p = Paths.get(getClass().classLoader.getResource('test_multi_config_no_action.json').getPath())
    AppSecSystem.active = false

    when:
    appSecConfigService.init()
    then:
    1 * config.getAppSecRulesFile() >> (p as String)

    when:
    appSecConfigService.maybeSubscribeConfigPolling()

    then:
    2 * config.getAppSecActivation() >> ProductActivation.ENABLED_INACTIVE
    1 * poller.addListener(Product.ASM_FEATURES, _, _)
    1 * poller.addListener(Product.DEBUG, _, _)
    1 * poller.addConfigurationEndListener(_)
    0 * poller.addListener(*_)
  }

  void 'can load from a different location'() {
    setup:
    Path p = Paths.get(getClass().classLoader.getResource('test_multi_config_no_action.json').getPath())
    String capturedPath = null

    when:
    appSecConfigService.init()

    then:
    1 * config.getAppSecRulesFile() >> { capturedPath = p.toString(); return p.toString() }
    capturedPath == p.toString()
    noExceptionThrown()
  }

  void 'aborts if alt config location does not exist'() {
    when:
    appSecConfigService.init()

    then:
    1 * config.getAppSecRulesFile() >> '/file/that/does/not/exist'
    thrown AbortStartupException
  }

  void 'aborts if alt config file is not valid json'() {
    setup:
    Path p = Files.createTempFile('appsec', '.json')
    p.toFile() << 'THIS IS NOT JSON'

    when:
    appSecConfigService.init()

    then:
    1 * config.getAppSecRulesFile() >> (p as String)
    thrown AbortStartupException
  }

  void 'provides initial subconfiguration upon subscription'() {
    AppSecModuleConfigurer.SubconfigListener listener = Stub()

    setup:
    appSecConfigService.init()
    appSecConfigService.maybeSubscribeConfigPolling()

    expect:
    AppSecConfigService.TransactionalAppSecModuleConfigurer configurer = appSecConfigService.createAppSecModuleConfigurer()
    configurer.addSubConfigListener("waf", listener)
  }

  static class SavedListeners {
    ProductListener savedWafDataChangesListener
    ConfigurationDeserializer<AppSecFeatures> savedFeaturesDeserializer
    ConfigurationChangesTypedListener<AppSecFeatures> savedFeaturesListener
    ConfigurationEndListener savedConfEndListener
  }

  void 'activation without custom config provides valid configuration'() {
    AppSecModuleConfigurer.SubconfigListener subconfigListener = Mock()

    when:
    AppSecSystem.active = false
    config.getAppSecActivation() >> ProductActivation.ENABLED_INACTIVE
    appSecConfigService.init()
    appSecConfigService.maybeSubscribeConfigPolling()
    def configurer = appSecConfigService.createAppSecModuleConfigurer()
    configurer.addSubConfigListener("waf", subconfigListener)
    configurer.commit()

    then:
    1 * poller.addListener(Product.ASM_FEATURES, _, _) >> {
      listeners.savedFeaturesDeserializer = it[1]
      listeners.savedFeaturesListener = it[2]
    }
    1 * poller.addListener(Product.DEBUG, _, _)
    1 * poller.addConfigurationEndListener(_) >> {
      listeners.savedConfEndListener = it[0]
    }
    1 * poller.addCapabilities(CAPABILITY_ASM_ACTIVATION)
    1 * poller.addCapabilities(CAPABILITY_ASM_AUTO_USER_INSTRUM_MODE)
    1 * poller.addCapabilities({ it & datadog.remoteconfig.Capabilities.CAPABILITY_ASM_SCA_VULNERABILITY_DETECTION })
    0 * poller._

    when:
    listeners.savedFeaturesListener.accept(
      'ignored config key',
      listeners.savedFeaturesDeserializer.deserialize(
      '{"asm":{"enabled": true}}'.bytes), null)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    1 * subconfigListener.onNewSubconfig(_, _)
    AppSecSystem.active
    1 * poller.addListener(Product.ASM_DD, _)
    1 * poller.addListener(Product.ASM, _)
    1 * poller.addListener(Product.ASM_DATA, _)
    1 * poller.addCapabilities(_)
  }

  void 'provides updated configuration to waf subscription'() {
    AppSecModuleConfigurer.SubconfigListener subconfigListener = Mock()
    AppSecSystem.active = false
    config.getAppSecActivation() >> ProductActivation.ENABLED_INACTIVE
    config.isAppSecRaspEnabled() >> true
    appSecConfigService.init()

    when:
    appSecConfigService.maybeSubscribeConfigPolling()
    def configurer = appSecConfigService.createAppSecModuleConfigurer()
    configurer.addSubConfigListener("waf", subconfigListener)
    configurer.commit()

    then:
    1 * poller.addListener(Product.ASM_FEATURES, _, _) >> {
      listeners.savedFeaturesDeserializer = it[1]
      listeners.savedFeaturesListener = it[2]
    }
    1 * poller.addListener(Product.DEBUG, _, _)
    1 * poller.addConfigurationEndListener(_) >> {
      listeners.savedConfEndListener = it[0]
    }
    1 * poller.addCapabilities(CAPABILITY_ASM_ACTIVATION)
    1 * poller.addCapabilities(CAPABILITY_ASM_AUTO_USER_INSTRUM_MODE)
    1 * poller.addCapabilities({ it & datadog.remoteconfig.Capabilities.CAPABILITY_ASM_SCA_VULNERABILITY_DETECTION })
    0 * poller._

    when:
    listeners.savedFeaturesListener.accept(
      'asm_features_activation',
      listeners.savedFeaturesDeserializer.deserialize(
      '{"asm":{"enabled": true}}'.bytes), null)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    1 * subconfigListener.onNewSubconfig(_ as String, _)
    AppSecSystem.active
    1 * poller.addListener(Product.ASM_DD, _) >> {
      listeners.savedWafDataChangesListener = it[1]
    }
    1 * poller.addListener(Product.ASM_DATA, _)
    1 * poller.addListener(Product.ASM, _)
    1 * poller.addCapabilities(CAPABILITY_ASM_DD_RULES
      | CAPABILITY_ASM_DD_MULTICONFIG
      | CAPABILITY_ASM_IP_BLOCKING
      | CAPABILITY_ASM_EXCLUSIONS
      | CAPABILITY_ASM_EXCLUSION_DATA
      | CAPABILITY_ASM_REQUEST_BLOCKING
      | CAPABILITY_ASM_USER_BLOCKING
      | CAPABILITY_ASM_CUSTOM_RULES
      | CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE
      | CAPABILITY_ASM_TRUSTED_IPS
      | CAPABILITY_ASM_PROCESSOR_OVERRIDES
      | CAPABILITY_ASM_CUSTOM_DATA_SCANNERS
      | CAPABILITY_ASM_RASP_SQLI
      | CAPABILITY_ASM_RASP_SSRF
      | CAPABILITY_ASM_RASP_CMDI
      | CAPABILITY_ASM_RASP_SHI
      | CAPABILITY_ENDPOINT_FINGERPRINT
      | CAPABILITY_ASM_SESSION_FINGERPRINT
      | CAPABILITY_ASM_NETWORK_FINGERPRINT
      | CAPABILITY_ASM_HEADER_FINGERPRINT
      | CAPABILITY_ASM_TRACE_TAGGING_RULES
      | CAPABILITY_ASM_EXTENDED_DATA_COLLECTION)
    0 * poller._

    when:
    listeners.savedFeaturesListener.accept(
      'asm_features_activation',
      listeners.savedFeaturesDeserializer.deserialize(
      '{"asm":{"enabled": true}}'.bytes), null)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    1 * subconfigListener.onNewSubconfig(_ as String, _)
    AppSecSystem.active

    when:
    // AppSec is ACTIVE - rules trigger subscriptions
    listeners.savedWafDataChangesListener.accept(
      'ignored config key' as ConfigKey,
      '''{
        "rules": [
          {
            "id": "foo",
            "name": "foo",
            "tags": {
              "type": "php_code_injection",
              "crs_id": "933140",
              "category": "attack_attempt",
              "cwe": "94",
              "capec": "1000/225/122/17/650",
              "confidence": "1",
              "module": "waf"
            },
            "conditions": [
              {
                "operator": "ip_match",
                "parameters": {
                  "data": "suspicious_ips_data_id",
                  "inputs": [
                    {
                      "address": "http.client_ip"
                    }
                  ]
                }
              }
            ],
            "type": "",
            "data": []
          }
        ]
      }'''.getBytes(), null)
    listeners.savedWafDataChangesListener.accept(
      'ignored config key' as ConfigKey,
      '''{"rules_override": [{"rules_target": [{"rule_id": "foo"}], "enabled": false}]}'''.getBytes(), null)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    1 * subconfigListener.onNewSubconfig(_, _)

    when:
    listeners.savedFeaturesListener.accept('asm_features_activation',
      listeners.savedFeaturesDeserializer.deserialize('{"asm":{"enabled": false}}'.bytes),
      NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    !AppSecSystem.active

    when: 'switch back to enabled'
    listeners.savedFeaturesListener.accept('asm_features_activation',
      listeners.savedFeaturesDeserializer.deserialize('{"asm":{"enabled": true}}'.bytes),
      NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then: 'it is enabled again'
    AppSecSystem.active

    when: 'asm are not set'
    listeners.savedFeaturesListener.accept('asm_features_activation',
      null,
      NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then: 'it is disabled (<not set> == false)'
    !AppSecSystem.active

    when: 'switch back to enabled'
    listeners.savedFeaturesListener.accept('asm_features_activation',
      listeners.savedFeaturesDeserializer.deserialize('{"asm":{"enabled": true}}'.bytes),
      NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then: 'it is enabled again'
    AppSecSystem.active

    when: 'asm features are not set'
    listeners.savedFeaturesListener.accept('asm_features_activation',
      null,
      NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then: 'it is disabled (<not set> == false)'
    !AppSecSystem.active
  }

  void 'configuration pull out'() {
    AppSecModuleConfigurer.SubconfigListener subconfigListener = Mock()

    when:
    AppSecSystem.active = false
    config.getAppSecActivation() >> ProductActivation.ENABLED_INACTIVE
    appSecConfigService.init()
    appSecConfigService.maybeSubscribeConfigPolling()
    def configurer = appSecConfigService.createAppSecModuleConfigurer()
    configurer.addSubConfigListener("waf", subconfigListener)
    configurer.commit()

    then:
    1 * poller.addListener(Product.ASM_FEATURES, _, _) >> {
      listeners.savedFeaturesDeserializer = it[1]
      listeners.savedFeaturesListener = it[2]
    }
    1 * poller.addListener(Product.DEBUG, _, _)
    1 * poller.addConfigurationEndListener(_) >> {
      listeners.savedConfEndListener = it[0]
    }
    1 * poller.addCapabilities(CAPABILITY_ASM_ACTIVATION)
    1 * poller.addCapabilities(CAPABILITY_ASM_AUTO_USER_INSTRUM_MODE)
    1 * poller.addCapabilities({ it & datadog.remoteconfig.Capabilities.CAPABILITY_ASM_SCA_VULNERABILITY_DETECTION })
    0 * poller._

    when:
    listeners.savedFeaturesListener.accept('asm_features conf',
      listeners.savedFeaturesDeserializer.deserialize('{"asm":{"enabled": true}}'.bytes),
      NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    AppSecSystem.active
    1 * poller.addListener(Product.ASM_DD, _) >> {
      listeners.savedWafDataChangesListener = it[1]
    }
    1 * poller.addListener(Product.ASM_DATA, _)
    1 * poller.addListener(Product.ASM, _)
    1 * poller.addCapabilities(CAPABILITY_ASM_DD_RULES
      | CAPABILITY_ASM_DD_MULTICONFIG
      | CAPABILITY_ASM_IP_BLOCKING
      | CAPABILITY_ASM_EXCLUSIONS
      | CAPABILITY_ASM_EXCLUSION_DATA
      | CAPABILITY_ASM_REQUEST_BLOCKING
      | CAPABILITY_ASM_USER_BLOCKING
      | CAPABILITY_ASM_CUSTOM_RULES
      | CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE
      | CAPABILITY_ASM_TRUSTED_IPS
      | CAPABILITY_ASM_PROCESSOR_OVERRIDES
      | CAPABILITY_ASM_CUSTOM_DATA_SCANNERS
      | CAPABILITY_ENDPOINT_FINGERPRINT
      | CAPABILITY_ASM_SESSION_FINGERPRINT
      | CAPABILITY_ASM_NETWORK_FINGERPRINT
      | CAPABILITY_ASM_HEADER_FINGERPRINT
      | CAPABILITY_ASM_TRACE_TAGGING_RULES
      | CAPABILITY_ASM_EXTENDED_DATA_COLLECTION)
    0 * poller._

    when:
    listeners.savedWafDataChangesListener.accept(
      new ParsedConfigKey('asm_dd config', 'null', 1, 'null', 'null'),
      '''{
        "rules": [
          {
            "id": "foo",
            "name": "foo",
            "tags": {
              "type": "php_code_injection",
              "crs_id": "933140",
              "category": "attack_attempt",
              "cwe": "94",
              "capec": "1000/225/122/17/650",
              "confidence": "1",
              "module": "waf"
            },
            "conditions": [
              {
                "operator": "ip_match",
                "parameters": {
                  "data": "suspicious_ips_data_id",
                  "inputs": [
                    {
                      "address": "http.client_ip"
                    }
                  ]
                }
              }
            ],
            "type": "",
            "data": []
          }
        ]
      }'''.getBytes(), null)
    listeners.savedWafDataChangesListener.accept(
      new ParsedConfigKey('asm conf', 'null', 1, 'null', 'null'),
      '''{
        "rules_override": [
          {
            "rules_target": [
              { "rule_id": "foo" }
            ],
            "enabled": false
          }
        ]
      }'''.getBytes(), null)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    1 * subconfigListener.onNewSubconfig(_, _)

    when:
    listeners.savedWafDataChangesListener.accept(
      new ParsedConfigKey('asm_dd config', 'null', 1, 'null', 'null'), null, null)
    listeners.savedWafDataChangesListener.accept(
      new ParsedConfigKey('asm conf', 'null', 1, 'null', 'null'), null, null)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    noExceptionThrown()
  }

  void 'stopping appsec unsubscribes from the poller'() {
    setup:
    appSecConfigService.maybeSubscribeConfigPolling()

    when:
    appSecConfigService.close()
    poller = null

    then:
    1 * poller.removeCapabilities(CAPABILITY_ASM_ACTIVATION
      | CAPABILITY_ASM_DD_RULES
      | CAPABILITY_ASM_DD_MULTICONFIG
      | CAPABILITY_ASM_IP_BLOCKING
      | CAPABILITY_ASM_EXCLUSIONS
      | CAPABILITY_ASM_EXCLUSION_DATA
      | CAPABILITY_ASM_REQUEST_BLOCKING
      | CAPABILITY_ASM_USER_BLOCKING
      | CAPABILITY_ASM_CUSTOM_RULES
      | CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE
      | CAPABILITY_ASM_TRUSTED_IPS
      | CAPABILITY_ASM_PROCESSOR_OVERRIDES
      | CAPABILITY_ASM_CUSTOM_DATA_SCANNERS
      | CAPABILITY_ASM_RASP_SQLI
      | CAPABILITY_ASM_RASP_SSRF
      | CAPABILITY_ASM_RASP_CMDI
      | CAPABILITY_ASM_RASP_SHI
      | CAPABILITY_ASM_RASP_LFI
      | CAPABILITY_ASM_AUTO_USER_INSTRUM_MODE
      | CAPABILITY_ENDPOINT_FINGERPRINT
      | CAPABILITY_ASM_SESSION_FINGERPRINT
      | CAPABILITY_ASM_NETWORK_FINGERPRINT
      | CAPABILITY_ASM_HEADER_FINGERPRINT
      | CAPABILITY_ASM_TRACE_TAGGING_RULES
      | CAPABILITY_ASM_EXTENDED_DATA_COLLECTION)
    1 * poller.removeListeners(Product.DEBUG)
    1 * poller.removeCapabilities({ it & datadog.remoteconfig.Capabilities.CAPABILITY_ASM_SCA_VULNERABILITY_DETECTION })
    4 * poller.removeListeners(_)
    1 * poller.removeConfigurationEndListener(_)
    1 * poller.stop()
  }

  void 'update auto user instrum mode via remote-config'() {
    given:
    def listeners = new SavedListeners()

    when:
    appSecConfigService.init()
    appSecConfigService.maybeSubscribeConfigPolling()

    then:
    1 * poller.addListener(Product.ASM_FEATURES, _, _) >> {
      listeners.savedFeaturesDeserializer = it[1]
      listeners.savedFeaturesListener = it[2]
    }
    1 * poller.addCapabilities(CAPABILITY_ASM_AUTO_USER_INSTRUM_MODE)
    1 * poller.addConfigurationEndListener(_) >> {
      listeners.savedConfEndListener = it[0]
    }

    when:
    listeners.savedFeaturesListener.accept('asm_auto_user_instrum', mode, null)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    UserIdCollectionMode.get() == expected

    where:
    mode                              | expected
    null                              | IDENTIFICATION
    autoUserInstrum(null)             | IDENTIFICATION
    autoUserInstrum('identification') | IDENTIFICATION
    autoUserInstrum('anonymization')  | ANONYMIZATION
    autoUserInstrum('disabled')       | DISABLED
    autoUserInstrum('yolo')           | DISABLED
  }

  void 'RASP capabilities are not sent when RASP is not fully enabled '() {
    setup:
    AppSecModuleConfigurer.SubconfigListener subconfigListener = Mock()
    def raspCapabilities = [CAPABILITY_ASM_RASP_SQLI: CAPABILITY_ASM_RASP_SQLI,
      CAPABILITY_ASM_RASP_SSRF: CAPABILITY_ASM_RASP_SSRF,
      CAPABILITY_ASM_RASP_CMDI: CAPABILITY_ASM_RASP_CMDI,
      CAPABILITY_ASM_RASP_SHI : CAPABILITY_ASM_RASP_SHI,
      CAPABILITY_ASM_RASP_LFI : CAPABILITY_ASM_RASP_LFI]

    when:
    AppSecSystem.active = true
    config.getAppSecActivation() >> ProductActivation.FULLY_ENABLED
    config.isAppSecRaspEnabled() >> false
    appSecConfigService.init()
    appSecConfigService.maybeSubscribeConfigPolling()
    def configurer = appSecConfigService.createAppSecModuleConfigurer()
    configurer.addSubConfigListener("waf", subconfigListener)
    configurer.commit()

    then:
    poller.addCapabilities(_) >> {
      final caps = it[0] as long
      final found = raspCapabilities.findAll { (it.value & caps) > 0 }.collect { it.key }
      assert found.isEmpty()
    }
  }

  def 'test AppSecConfigChangesListener listener'() {
    ProductListener listener = new AppSecConfigServiceImpl.AppSecConfigChangesListener()
    when:
    listener.remove('my_config' as ConfigKey, null) // unexisting config

    then:
    thrown RuntimeException

    when:
    def waf = [waf: null] as Map<String, Object> // wrong input
    listener.accept('my_config' as ConfigKey, waf, null)

    then:
    thrown RuntimeException
  }

  void 'InvalidRuleSetException is thrown when rules are not configured correctly' () {
    setup:
    // Mock WafMetricCollector
    WafMetricCollector wafMetricCollector = Mock(WafMetricCollector)
    WafMetricCollector.INSTANCE = wafMetricCollector

    // Create a temporary file with invalid WAF configuration
    Path p = Files.createTempFile('appsec', '.json')
    p.toFile() << '''{
      "version": "2.2",
      "rules": [
        {
          "id": "invalid-rule",
          "name": "Invalid Rule",
          "tags": {
            "type": "invalid_type",
            "category": "invalid_category"
          },
          "conditions": [
            {
              "operator": "invalid_operator",
              "parameters": {
                "invalid_param": "invalid_value"
              }
            }
          ],
          "type": "invalid_type",
          "data": []
        }
      ]
    }'''

    when:
    appSecConfigService.init()

    then:
    1 * config.getAppSecRulesFile() >> (p as String)
    1 * wafMetricCollector.addWafConfigError(_ as Integer)
    thrown RuntimeException

    cleanup:
    WafMetricCollector.INSTANCE = ORIGINAL_METRIC_COLLECTOR
    p.toFile().delete()
  }

  // https://github.com/DataDog/dd-trace-java/issues/9159
  void 'test initialization issues while applying remote config'() {
    setup:
    final key = new ParsedConfigKey('Test', '1234', 1, 'ASM_DD', 'ID')
    final service = new AppSecConfigServiceImpl(config, poller, reconf)
    config.getAppSecActivation() >> ProductActivation.ENABLED_INACTIVE

    when:
    service.maybeSubscribeConfigPolling()

    then:
    1 * poller.addListener(Product.ASM_DD, _) >> {
      listeners.savedWafDataChangesListener = it[1]
    }

    when:
    listeners.savedWafDataChangesListener.accept(key, '''{"rules_override": [{"rules_target": [{"rule_id": "foo"}], "enabled": false}]}'''.getBytes(), NOOP)

    then:
    noExceptionThrown()
  }

  void 'config keys are added and removed to the set when receiving ASM_DD payloads'() {
    setup:
    final key = new ParsedConfigKey('Test', '1234', 1, 'ASM_DD', 'ID')
    final service = new AppSecConfigServiceImpl(config, poller, reconf)
    config.getAppSecActivation() >> ProductActivation.ENABLED_INACTIVE

    when:
    service.maybeSubscribeConfigPolling()

    then:
    1 * poller.addListener(Product.ASM_DD, _) >> {
      listeners.savedWafDataChangesListener = it[1]
    }
    1 * poller.addListener(Product.ASM_FEATURES, _, _) >> {
      listeners.savedFeaturesDeserializer = it[1]
      listeners.savedFeaturesListener = it[2]
    }

    when:
    listeners.savedFeaturesListener.accept('asm_features conf',
      listeners.savedFeaturesDeserializer.deserialize('{"asm":{"enabled": true}}'.bytes),
      NOOP)

    then:
    service.usedDDWafConfigKeys.empty

    when:
    listeners.savedWafDataChangesListener.accept(key, '''{"rules_override": [{"rules_target": [{"rule_id": "foo"}], "enabled": false}]}'''.getBytes(), NOOP)

    then:
    service.usedDDWafConfigKeys.toList() == [key.toString()]

    when:
    listeners.savedWafDataChangesListener.remove(key, NOOP)

    then:
    service.usedDDWafConfigKeys.empty
  }

  void 'test that empty configurations are acknowledged'() {
    given:
    final key = new ParsedConfigKey('Test', '1234', 1, 'ASM_DD', 'ID')

    when:
    AppSecSystem.active = true
    config.getAppSecActivation() >> ProductActivation.FULLY_ENABLED
    final service = new AppSecConfigServiceImpl(config, poller, reconf)
    service.init()
    service.maybeSubscribeConfigPolling()

    then:
    1 * poller.addListener(Product.ASM_DATA, _) >> {
      listeners.savedWafDataChangesListener = it[1]
    }
    1 * poller.addConfigurationEndListener(_) >> {
      listeners.savedConfEndListener = it[0]
    }

    when:
    listeners.savedWafDataChangesListener.accept(key, '{}'.bytes, NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    noExceptionThrown()

    when:
    listeners.savedWafDataChangesListener.accept(key, null, NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    noExceptionThrown()
  }

  void 'subscribes to ASM_SCA product when configuration poller is active'() {
    setup:
    appSecConfigService.init()
    AppSecSystem.active = false
    config.getAppSecActivation() >> ProductActivation.ENABLED_INACTIVE

    when:
    appSecConfigService.maybeSubscribeConfigPolling()

    then:
    1 * poller.addListener(Product.DEBUG, AppSecSCAConfigDeserializer.INSTANCE, _)
    1 * poller.addCapabilities({ it & datadog.remoteconfig.Capabilities.CAPABILITY_ASM_SCA_VULNERABILITY_DETECTION })
  }

  void 'unsubscribes from ASM_SCA product on close'() {
    setup:
    appSecConfigService.init()
    AppSecSystem.active = false
    config.getAppSecActivation() >> ProductActivation.ENABLED_INACTIVE
    appSecConfigService.maybeSubscribeConfigPolling()

    when:
    appSecConfigService.close()

    then:
    1 * poller.removeListeners(Product.DEBUG)
    1 * poller.removeCapabilities({ it & datadog.remoteconfig.Capabilities.CAPABILITY_ASM_SCA_VULNERABILITY_DETECTION })
  }


  private static AppSecFeatures autoUserInstrum(String mode) {
    return new AppSecFeatures().tap { features ->
      features.autoUserInstrum = new AppSecFeatures.AutoUserInstrum().tap { instrum ->
        instrum.mode = mode
      }
    }
  }
}
