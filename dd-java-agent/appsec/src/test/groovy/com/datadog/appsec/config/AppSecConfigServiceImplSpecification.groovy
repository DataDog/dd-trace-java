package com.datadog.appsec.config

import com.datadog.appsec.AppSecSystem
import com.datadog.appsec.api.security.ApiSecurityRequestSampler
import com.datadog.appsec.util.AbortStartupException
import datadog.remoteconfig.ConfigurationChangesTypedListener
import datadog.remoteconfig.ConfigurationDeserializer
import datadog.remoteconfig.ConfigurationEndListener
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.PollingRateHinter
import datadog.remoteconfig.Product
import datadog.trace.api.ProductActivation
import datadog.trace.test.util.DDSpecification

import java.nio.file.Files
import java.nio.file.Path

import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_API_SECURITY_SAMPLE_RATE
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_ACTIVATION
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_CUSTOM_RULES
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_DD_RULES
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_EXCLUSIONS
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_IP_BLOCKING
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_RASP_SQLI
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_REQUEST_BLOCKING
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_TRUSTED_IPS
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_USER_BLOCKING

class AppSecConfigServiceImplSpecification extends DDSpecification {

  ConfigurationPoller poller = Mock()
  def config = Mock(Class.forName('datadog.trace.api.Config'))
  ApiSecurityRequestSampler sampler = Mock(ApiSecurityRequestSampler)
  AppSecModuleConfigurer.Reconfiguration reconf = Stub()
  AppSecConfigServiceImpl appSecConfigService = new AppSecConfigServiceImpl(config, poller, sampler, reconf)

  void cleanup() {
    appSecConfigService?.close()
  }

  void 'maybeStartConfigPolling subscribes to the configuration poller'() {
    setup:
    appSecConfigService.init()

    when:
    appSecConfigService.maybeSubscribeConfigPolling()

    then:
    1 * config.getAppSecActivation() >> ProductActivation.ENABLED_INACTIVE
    1 * poller.addListener(Product.ASM_DD, _, _)
    1 * poller.addListener(Product.ASM_FEATURES, 'asm_features_activation', _, _)
    1 * poller.addListener(Product.ASM_FEATURES, 'asm_api_security', _, _)
    1 * poller.addListener(Product.ASM, _, _)
    1 * poller.addListener(Product.ASM_DATA, _, _)
    1 * poller.addConfigurationEndListener(_)
    1 * poller.addCapabilities(CAPABILITY_ASM_ACTIVATION)
  }

  void 'no subscription to ASM_FEATURES if appsec is fully enabled'() {
    setup:
    appSecConfigService.init()

    when:
    appSecConfigService.maybeSubscribeConfigPolling()

    then:
    1 * config.getAppSecActivation() >> ProductActivation.FULLY_ENABLED
    1 * poller.addListener(Product.ASM_DD, _, _)
    1 * poller.addListener(Product.ASM_FEATURES, 'asm_api_security', _, _)
    1 * poller.addListener(Product.ASM, _, _)
    1 * poller.addListener(Product.ASM_DATA, _, _)
    1 * poller.addConfigurationEndListener(_)
    0 * poller.addListener(*_)
    0 * poller.addCapabilities(CAPABILITY_ASM_ACTIVATION)
  }

  void 'no subscription to ASM_FEATURES if appsec is fully disabled'() {
    setup:
    appSecConfigService.init()

    when:
    appSecConfigService.maybeSubscribeConfigPolling()

    then:
    1 * config.getAppSecActivation() >> ProductActivation.FULLY_DISABLED
    1 * poller.addListener(Product.ASM_DD, _, _)
    1 * poller.addListener(Product.ASM_FEATURES, 'asm_api_security', _, _)
    1 * poller.addListener(Product.ASM, _, _)
    1 * poller.addListener(Product.ASM_DATA, _, _)
    1 * poller.addConfigurationEndListener(_)
    0 * poller.addListener(*_)
    0 * poller.addCapabilities(CAPABILITY_ASM_ACTIVATION)
  }

  void 'no subscription to ASM ASM_DD ASM_DATA if custom rules are provided'() {
    setup:
    Path p = Files.createTempFile('appsec', '.json')
    p.toFile() << '{"version":"2.0", "rules": []}'

    when:
    appSecConfigService.init()
    then:
    1 * config.getAppSecRulesFile() >> (p as String)

    when:
    appSecConfigService.maybeSubscribeConfigPolling()

    then:
    2 * config.getAppSecActivation() >> ProductActivation.ENABLED_INACTIVE
    1 * poller.addListener(Product.ASM_FEATURES, 'asm_features_activation', _, _)
    1 * poller.addListener(Product.ASM_FEATURES, 'asm_api_security', _, _)
    1 * poller.addConfigurationEndListener(_)
    0 * poller.addListener(*_)
  }

  void 'can load from a different location'() {
    setup:
    Path p = Files.createTempFile('appsec', '.json')
    p.toFile() << '{"version":"2.0", "rules": []}'
    AppSecModuleConfigurer.SubconfigListener listener = Stub()

    when:
    appSecConfigService.init()

    then:
    1 * config.getAppSecRulesFile() >> (p as String)
    def expected = AppSecConfig.valueOf([version: '2.0', rules: []])
    CurrentAppSecConfig actual = appSecConfigService.createAppSecModuleConfigurer().addSubConfigListener('waf', listener).get()
    actual.ddConfig == expected
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
    configurer.addSubConfigListener("waf", listener).get() instanceof CurrentAppSecConfig
    configurer.addSubConfigListener("waf2", listener) == Optional.empty()
  }

  static class SavedListeners {
    ConfigurationDeserializer<AppSecConfig> savedConfDeserializer
    ConfigurationChangesTypedListener<AppSecConfig> savedConfChangesListener
    ConfigurationDeserializer<List<Map<String, Object>>> savedWafDataDeserializer
    ConfigurationChangesTypedListener<List<Map<String, Object>>> savedWafDataChangesListener
    ConfigurationDeserializer<Map<String, Boolean>> savedWafRulesOverrideDeserializer
    ConfigurationChangesTypedListener<Map<String, Boolean>> savedWafRulesOverrideListener
    ConfigurationDeserializer<AppSecFeatures> savedFeaturesDeserializer
    ConfigurationChangesTypedListener<AppSecFeatures> savedFeaturesListener
    ConfigurationEndListener savedConfEndListener
  }

  void 'activation without custom config provides valid configuration'() {
    AppSecModuleConfigurer.SubconfigListener subconfigListener = Mock()
    SavedListeners listeners = new SavedListeners()

    when:
    AppSecSystem.active = false
    appSecConfigService.init()
    appSecConfigService.maybeSubscribeConfigPolling()
    def configurer = appSecConfigService.createAppSecModuleConfigurer()
    configurer.addSubConfigListener("waf", subconfigListener)
    configurer.commit()

    then:
    1 * config.getAppSecRulesFile() >> null
    1 * config.getAppSecActivation() >> ProductActivation.ENABLED_INACTIVE
    1 * poller.addListener(Product.ASM_FEATURES, 'asm_features_activation', _, _) >> {
      listeners.savedFeaturesDeserializer = it[2]
      listeners.savedFeaturesListener = it[3]
    }
    1 * poller.addListener(Product.ASM_FEATURES, 'asm_api_security', _, _)
    1 * poller.addConfigurationEndListener(_) >> { listeners.savedConfEndListener = it[0] }
    _ * poller._
    0 * _._

    when:
    listeners.savedFeaturesListener.accept(
      'ignored config key',
      listeners.savedFeaturesDeserializer.deserialize(
      '{"asm":{"enabled": true}}'.bytes), null)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    1 * subconfigListener.onNewSubconfig({ CurrentAppSecConfig casc -> casc.ddConfig != null }, _)
    0 * _._
    AppSecSystem.active == true
  }

  void 'provides updated configuration to waf subscription'() {
    AppSecModuleConfigurer.SubconfigListener subconfigListener = Mock()
    SavedListeners listeners = new SavedListeners()
    Optional<CurrentAppSecConfig> initialWafConfig

    when:
    AppSecSystem.active = false
    appSecConfigService.init()
    appSecConfigService.maybeSubscribeConfigPolling()
    def configurer = appSecConfigService.createAppSecModuleConfigurer()
    initialWafConfig = configurer.addSubConfigListener("waf", subconfigListener)
    configurer.commit()

    then:
    1 * config.getAppSecRulesFile() >> null
    1 * config.getAppSecActivation() >> ProductActivation.ENABLED_INACTIVE
    1 * poller.addListener(Product.ASM_DD, _, _) >> {
      listeners.savedConfDeserializer = it[1]
      listeners.savedConfChangesListener = it[2]
    }
    1 * poller.addListener(Product.ASM_DATA, _, _) >> {
      listeners.savedWafDataDeserializer = it[1]
      listeners.savedWafDataChangesListener = it[2]
    }
    1 * poller.addListener(Product.ASM, _, _) >> {
      listeners.savedWafRulesOverrideDeserializer = it[1]
      listeners.savedWafRulesOverrideListener = it[2]
    }
    1 * poller.addListener(Product.ASM_FEATURES, 'asm_features_activation', _, _) >> {
      listeners.savedFeaturesDeserializer = it[2]
      listeners.savedFeaturesListener = it[3]
    }
    1 * poller.addListener(Product.ASM_FEATURES, 'asm_api_security', _, _)
    1 * poller.addConfigurationEndListener(_) >> { listeners.savedConfEndListener = it[0] }
    1 * poller.addCapabilities(CAPABILITY_ASM_ACTIVATION)
    1 * poller.addCapabilities(CAPABILITY_ASM_API_SECURITY_SAMPLE_RATE)
    1 * poller.addCapabilities(CAPABILITY_ASM_DD_RULES
      | CAPABILITY_ASM_IP_BLOCKING
      | CAPABILITY_ASM_EXCLUSIONS
      | CAPABILITY_ASM_REQUEST_BLOCKING
      | CAPABILITY_ASM_USER_BLOCKING
      | CAPABILITY_ASM_CUSTOM_RULES
      | CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE
      | CAPABILITY_ASM_TRUSTED_IPS
      | CAPABILITY_ASM_RASP_SQLI)
    0 * _._
    initialWafConfig.get() != null

    when:
    // AppSec is INACTIVE - rules should not trigger subscriptions
    listeners.savedConfChangesListener.accept(
      'ignored config key',
      listeners.savedConfDeserializer.deserialize(
      '{"version": "1.0"}'.bytes), null)

    then:
    0 * _._

    when:
    listeners.savedFeaturesListener.accept(
      'ignored config key',
      listeners.savedFeaturesDeserializer.deserialize(
      '{"asm":{"enabled": true}}'.bytes), null)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    1 * subconfigListener.onNewSubconfig(_ as CurrentAppSecConfig, _)
    0 * _._
    AppSecSystem.active == true

    when:
    // AppSec is ACTIVE - rules trigger subscriptions
    listeners.savedConfChangesListener.accept(
      'ignored config key',
      listeners.savedConfDeserializer.deserialize(
      '{"version": "2.0"}'.bytes), null)
    listeners.savedWafDataChangesListener.accept(
      'ignored config key',
      listeners.savedWafDataDeserializer.deserialize('{"rules_data":[{"id":"foo","type":"","data":[]}]}'.bytes), null)
    listeners.savedWafRulesOverrideListener.accept(
      'ignored config key',
      listeners.savedWafRulesOverrideDeserializer.deserialize('{"rules_override": [{"rules_target":[{"rule_id": "foo"}], "enabled":false}]}'.bytes), null)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    1 * subconfigListener.onNewSubconfig({ CurrentAppSecConfig casc ->
      casc.ddConfig == AppSecConfig.valueOf([version: '2.0'])
      casc.mergedUpdateConfig.rawConfig['rules_override'] == [
        [
          rules_target: [[rule_id: 'foo']],
          enabled     : false
        ]
      ]
      casc.mergedAsmData == [[data: [], id: 'foo', type: '']]
    }, _)
    0 * _._

    when:
    listeners.savedFeaturesListener.accept('asm_features_activation',
      listeners.savedFeaturesDeserializer.deserialize('{"asm":{"enabled": false}}'.bytes),
      PollingRateHinter.NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    0 * _._
    AppSecSystem.active == false

    when: 'switch back to enabled'
    listeners.savedFeaturesListener.accept('asm_features_activation',
      listeners.savedFeaturesDeserializer.deserialize('{"asm":{"enabled": true}}'.bytes),
      PollingRateHinter.NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then: 'it is enabled again'
    AppSecSystem.active == true

    when: 'asm are not set'
    listeners.savedFeaturesListener.accept('asm_features_activation',
      listeners.savedFeaturesDeserializer.deserialize('{}'.bytes),
      PollingRateHinter.NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then: 'it is disabled (<not set> == false)'
    1 * config.getAppSecActivation() >> ProductActivation.ENABLED_INACTIVE
    AppSecSystem.active == false

    when: 'switch back to enabled'
    listeners.savedFeaturesListener.accept('asm_features_activation',
      listeners.savedFeaturesDeserializer.deserialize('{"asm":{"enabled": true}}'.bytes),
      PollingRateHinter.NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then: 'it is enabled again'
    AppSecSystem.active == true

    when: 'asm features are not set'
    listeners.savedFeaturesListener.accept('asm_features_activation',
      null,
      PollingRateHinter.NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then: 'it is disabled (<not set> == false)'
    AppSecSystem.active == false

    cleanup:
    AppSecSystem.active = true
  }

  void 'configuration pull out'() {
    AppSecModuleConfigurer.SubconfigListener subconfigListener = Mock()
    SavedListeners listeners = new SavedListeners()
    MergedAsmData mergedAsmData
    AppSecConfig mergedUpdateConfig

    when:
    appSecConfigService.init()
    appSecConfigService.maybeSubscribeConfigPolling()
    def configurer = appSecConfigService.createAppSecModuleConfigurer()
    configurer.addSubConfigListener("waf", subconfigListener)
    configurer.commit()

    then:
    1 * config.getAppSecRulesFile() >> null
    1 * config.getAppSecActivation() >> ProductActivation.ENABLED_INACTIVE
    1 * poller.addListener(Product.ASM_DD, _, _) >> {
      listeners.savedConfDeserializer = it[1]
      listeners.savedConfChangesListener = it[2]
    }
    1 * poller.addListener(Product.ASM_DATA, _, _) >> {
      listeners.savedWafDataDeserializer = it[1]
      listeners.savedWafDataChangesListener = it[2]
    }
    1 * poller.addListener(Product.ASM, _, _) >> {
      listeners.savedWafRulesOverrideDeserializer = it[1]
      listeners.savedWafRulesOverrideListener = it[2]
    }
    1 * poller.addListener(Product.ASM_FEATURES, 'asm_features_activation', _, _) >> {
      listeners.savedFeaturesDeserializer = it[2]
      listeners.savedFeaturesListener = it[3]
    }
    1 * poller.addListener(Product.ASM_FEATURES, _, _, _)
    1 * poller.addConfigurationEndListener(_) >> { listeners.savedConfEndListener = it[0] }
    1 * poller.addCapabilities(CAPABILITY_ASM_ACTIVATION)
    1 * poller.addCapabilities(CAPABILITY_ASM_API_SECURITY_SAMPLE_RATE)
    1 * poller.addCapabilities(CAPABILITY_ASM_DD_RULES
      | CAPABILITY_ASM_IP_BLOCKING
      | CAPABILITY_ASM_EXCLUSIONS
      | CAPABILITY_ASM_REQUEST_BLOCKING
      | CAPABILITY_ASM_USER_BLOCKING
      | CAPABILITY_ASM_CUSTOM_RULES
      | CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE
      | CAPABILITY_ASM_TRUSTED_IPS
      | CAPABILITY_ASM_RASP_SQLI)
    0 * _._

    when:
    listeners.savedConfChangesListener.accept(
      'asm_dd config',
      listeners.savedConfDeserializer.deserialize(
      '{"version": "2.0"}'.bytes), null)
    listeners.savedWafDataChangesListener.accept(
      'asm_data config',
      listeners.savedWafDataDeserializer.deserialize('{"rules_data":[{"id":"foo","type":"","data":[]}]}'.bytes), null)
    listeners.savedWafRulesOverrideListener.accept(
      'asm conf',
      listeners.savedWafRulesOverrideDeserializer.deserialize('{"rules_override": [{"rules_target":[{"rule_id": "foo"}], "enabled":false}]}'.bytes), null)
    listeners.savedFeaturesListener.accept('asm_features conf',
      listeners.savedFeaturesDeserializer.deserialize('{"asm":{"enabled": true}}'.bytes),
      PollingRateHinter.NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    1 * subconfigListener.onNewSubconfig(_, _) >> {
      CurrentAppSecConfig casc = it[0]
      mergedAsmData = casc.mergedAsmData
      mergedUpdateConfig = casc.mergedUpdateConfig
    }
    mergedUpdateConfig.numberOfRules == 0
    mergedUpdateConfig.rawConfig['rules_override'].isEmpty() == false
    mergedAsmData.isEmpty() == false

    when:
    listeners.savedConfChangesListener.accept('asm_dd config', null, null)
    listeners.savedWafDataChangesListener.accept('asm_data config', null, null)
    listeners.savedWafRulesOverrideListener.accept('asm conf', null, null)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    1 * subconfigListener.onNewSubconfig(_, _) >> {
      CurrentAppSecConfig casc = it[0]
      mergedAsmData = casc.mergedAsmData
      mergedUpdateConfig = casc.mergedUpdateConfig
    }

    mergedUpdateConfig.numberOfRules > 0
    mergedUpdateConfig.rawConfig['rules_override'].isEmpty() == true
    mergedAsmData.isEmpty() == true
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
      | CAPABILITY_ASM_IP_BLOCKING
      | CAPABILITY_ASM_EXCLUSIONS
      | CAPABILITY_ASM_REQUEST_BLOCKING
      | CAPABILITY_ASM_USER_BLOCKING
      | CAPABILITY_ASM_CUSTOM_RULES
      | CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE
      | CAPABILITY_ASM_TRUSTED_IPS
      | CAPABILITY_ASM_API_SECURITY_SAMPLE_RATE
      | CAPABILITY_ASM_RASP_SQLI)
    4 * poller.removeListeners(_)
    1 * poller.removeConfigurationEndListener(_)
    1 * poller.stop()
  }

  void 'config should not be created'() {
    def conf

    when:
    conf = AppSecConfig.valueOf(null)

    then:
    conf == null
  }

  void 'unsupported config version'() {
    when:
    AppSecConfig.valueOf([version: '99.0'])

    then:
    thrown IOException
  }

  void 'update sample rate via remote-config'() {
    given:
    def newConfig = new AppSecFeatures().tap { features ->
      features.apiSecurity = new AppSecFeatures.ApiSecurity().tap { api ->
        api.requestSampleRate = 0.2
      }
    }
    def listeners = new SavedListeners()

    when:
    appSecConfigService.init()
    appSecConfigService.maybeSubscribeConfigPolling()

    then:
    1 * poller.addListener(Product.ASM_FEATURES, 'asm_api_security', _, _) >> {
      listeners.savedFeaturesDeserializer = it[2]
      listeners.savedFeaturesListener = it[3]
    }
    1 * poller.addCapabilities(CAPABILITY_ASM_API_SECURITY_SAMPLE_RATE)

    when:
    listeners.savedFeaturesListener.accept('asm_api_security', newConfig, null)

    then:
    1 * sampler.setSampling(0.2F)
  }
}
