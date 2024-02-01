package com.datadog.appsec.config

import com.datadog.appsec.AppSecSystem
import com.datadog.appsec.util.AbortStartupException
import datadog.remoteconfig.ConfigurationChangesListener
import datadog.remoteconfig.ConfigurationChangesTypedListener
import datadog.remoteconfig.ConfigurationDeserializer
import datadog.remoteconfig.ConfigurationEndListener
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.Product
import datadog.trace.api.ProductActivation
import datadog.trace.test.util.DDSpecification

import java.nio.file.Files
import java.nio.file.Path

class AppSecConfigServiceImplSpecification extends DDSpecification {

  ConfigurationPoller poller = Mock()
  def config = Mock(Class.forName('datadog.trace.api.Config'))
  AppSecModuleConfigurer.Reconfiguration reconf = Stub()
  AppSecConfigServiceImpl appSecConfigService = new AppSecConfigServiceImpl(config, poller, reconf)

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
    1 * poller.addListener(Product.ASM, _, _)
    1 * poller.addListener(Product.ASM_DATA, _, _)
    1 * poller.addConfigurationEndListener(_)
  }

  void 'no subscription to ASM_FEATURES if appsec is fully enabled'() {
    setup:
    appSecConfigService.init()

    when:
    appSecConfigService.maybeSubscribeConfigPolling()

    then:
    1 * config.getAppSecActivation() >> ProductActivation.FULLY_ENABLED
    1 * poller.addListener(Product.ASM_DD, _, _)
    1 * poller.addListener(Product.ASM, _, _)
    1 * poller.addListener(Product.ASM_DATA, _, _)
    1 * poller.addConfigurationEndListener(_)
    0 * poller.addListener(*_)
  }

  void 'no subscription to ASM_FEATURES if appsec is fully disabled'() {
    setup:
    appSecConfigService.init()

    when:
    appSecConfigService.maybeSubscribeConfigPolling()

    then:
    1 * config.getAppSecActivation() >> ProductActivation.FULLY_DISABLED
    1 * poller.addListener(Product.ASM_DD, _, _)
    1 * poller.addListener(Product.ASM, _, _)
    1 * poller.addListener(Product.ASM_DATA, _, _)
    1 * poller.addConfigurationEndListener(_)
    0 * poller.addListener(*_)
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
      true
    }
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
      true
    }
    1 * poller.addListener(Product.ASM_DATA, _, _) >> {
      listeners.savedWafDataDeserializer = it[1]
      listeners.savedWafDataChangesListener = it[2]
    }
    1 * poller.addListener(Product.ASM, _, _) >> {
      listeners.savedWafRulesOverrideDeserializer = it[1]
      listeners.savedWafRulesOverrideListener = it[2]
    }
    1 * poller.addListener(Product.ASM_FEATURES, "asm_features_activation", _, _) >> {
      listeners.savedFeaturesDeserializer = it[2]
      listeners.savedFeaturesListener = it[3]
      true
    }
    1 * poller.addConfigurationEndListener(_) >> { listeners.savedConfEndListener = it[0] }
    1 * poller.addCapabilities(2L)
    1 * poller.addCapabilities(1980L)
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
      casc.mergedUpdateConfig.rawConfig['rules_override'] == [[
          rules_target: [[rule_id: 'foo']],
          enabled: false
        ]]
      casc.mergedAsmData == [[data:[], id: 'foo', type: '']]
    }, _)
    0 * _._

    when:
    listeners.savedFeaturesListener.accept('config_key',
      listeners.savedFeaturesDeserializer.deserialize('{"asm":{"enabled": false}}'.bytes),
      ConfigurationChangesListener.PollingRateHinter.NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then:
    0 * _._
    AppSecSystem.active == false

    when: 'switch back to enabled'
    listeners.savedFeaturesListener.accept('config_key',
      listeners.savedFeaturesDeserializer.deserialize('{"asm":{"enabled": true}}'.bytes),
      ConfigurationChangesListener.PollingRateHinter.NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then: 'it is enabled again'
    AppSecSystem.active == true

    when: 'asm are not set'
    listeners.savedFeaturesListener.accept('config_key',
      listeners.savedFeaturesDeserializer.deserialize('{}'.bytes),
      ConfigurationChangesListener.PollingRateHinter.NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then: 'it is disabled (<not set> == false)'
    AppSecSystem.active == false

    when: 'switch back to enabled'
    listeners.savedFeaturesListener.accept('config_key',
      listeners.savedFeaturesDeserializer.deserialize('{"asm":{"enabled": true}}'.bytes),
      ConfigurationChangesListener.PollingRateHinter.NOOP)
    listeners.savedConfEndListener.onConfigurationEnd()

    then: 'it is enabled again'
    AppSecSystem.active == true

    when: 'asm features are not set'
    listeners.savedFeaturesListener.accept('config_key',
      null,
      ConfigurationChangesListener.PollingRateHinter.NOOP)
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
      true
    }
    1 * poller.addListener(Product.ASM_DATA, _, _) >> {
      listeners.savedWafDataDeserializer = it[1]
      listeners.savedWafDataChangesListener = it[2]
    }
    1 * poller.addListener(Product.ASM, _, _) >> {
      listeners.savedWafRulesOverrideDeserializer = it[1]
      listeners.savedWafRulesOverrideListener = it[2]
    }
    1 * poller.addListener(Product.ASM_FEATURES, "asm_features_activation", _, _) >> {
      listeners.savedFeaturesDeserializer = it[2]
      listeners.savedFeaturesListener = it[3]
      true
    }
    1 * poller.addConfigurationEndListener(_) >> { listeners.savedConfEndListener = it[0] }
    1 * poller.addCapabilities(2L)
    1 * poller.addCapabilities(1980L)
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
      ConfigurationChangesListener.PollingRateHinter.NOOP)
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
    1 * poller.removeCapabilities(4030L)
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
}
