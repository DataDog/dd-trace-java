package com.datadog.appsec.config

import com.datadog.appsec.AppSecSystem
import com.datadog.appsec.util.AbortStartupException
import datadog.remoteconfig.ConfigurationChangesListener
import datadog.remoteconfig.ConfigurationDeserializer
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.Product
import datadog.trace.test.util.DDSpecification

import java.nio.file.Files
import java.nio.file.Path

class AppSecConfigServiceImplSpecification extends DDSpecification {

  ConfigurationPoller poller = Mock()
  def config = Mock(Class.forName('datadog.trace.api.Config'))
  AppSecModuleConfigurer.Reconfiguration reconf = Mock()
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
    1 * poller.addListener(Product.ASM_DD, _, _)
    1 * poller.addListener(Product.ASM_FEATURES, _, _)
  }

  void 'can load from a different location'() {
    setup:
    Path p = Files.createTempFile('appsec', '.json')
    p.toFile() << '{"version":"2.0", "rules": []}'
    AppSecModuleConfigurer.SubconfigListener listener = Mock()

    when:
    appSecConfigService.init()

    then:
    1 * config.getAppSecRulesFile() >> (p as String)
    def expected = AppSecConfig.valueOf([version: '2.0', rules: []])
    def actual = appSecConfigService.createAppSecModuleConfigurer().addSubConfigListener('waf', listener).get()
    actual == expected
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
    AppSecModuleConfigurer.SubconfigListener listener = Mock()

    setup:
    appSecConfigService.init()
    appSecConfigService.maybeSubscribeConfigPolling()

    expect:
    AppSecConfigService.TransactionalAppSecModuleConfigurer configurer = appSecConfigService.createAppSecModuleConfigurer()
    configurer.addSubConfigListener("waf", listener).get() instanceof AppSecConfig
    configurer.addSubConfigListener("waf2", listener) == Optional.empty()
  }

  void 'provides updated configuration to waf subscription'() {
    AppSecModuleConfigurer.SubconfigListener subconfigListener = Mock()
    AppSecModuleConfigurer.SubconfigListener wafDataListener = Mock()
    AppSecModuleConfigurer.SubconfigListener wafRulesOverrideListener = Mock()
    ConfigurationDeserializer<AppSecConfig> savedConfDeserializer
    ConfigurationChangesListener<AppSecConfig> savedConfChangesListener
    ConfigurationDeserializer<List<Map<String, Object>>> savedWafDataDeserializer
    ConfigurationChangesListener<List<Map<String, Object>>> savedWafDataChangesListener
    ConfigurationDeserializer<Map<String, Boolean>> savedWafRulesOverrideDeserializer
    ConfigurationChangesListener<Map<String, Boolean>> savedWafRulesOverrideListener
    ConfigurationDeserializer<AppSecFeatures> savedFeaturesDeserializer
    ConfigurationChangesListener<AppSecFeatures> savedFeaturesListener
    def initialWafConfig
    def initialWafData
    def initialRulesOverride

    when:
    AppSecSystem.ACTIVE = false
    appSecConfigService.init()
    appSecConfigService.maybeSubscribeConfigPolling()
    def configurer = appSecConfigService.createAppSecModuleConfigurer()
    initialWafConfig = configurer.addSubConfigListener("waf", subconfigListener)
    initialWafData = configurer.addSubConfigListener("waf_data", wafDataListener)
    initialRulesOverride = configurer.addSubConfigListener("waf_rules_override", wafRulesOverrideListener)
    configurer.commit()

    then:
    1 * config.getAppSecRulesFile() >> null
    1 * poller.addListener(Product.ASM_DD, _, _) >> {
      savedConfDeserializer = it[1]
      savedConfChangesListener = it[2]
      true
    }
    1 * poller.addListener(Product.ASM_DATA, _, _) >> {
      savedWafDataDeserializer = it[1]
      savedWafDataChangesListener = it[2]
    }
    1 * poller.addListener(Product.ASM, _, _) >> {
      savedWafRulesOverrideDeserializer = it[1]
      savedWafRulesOverrideListener = it[2]
    }
    1 * poller.addListener(Product.ASM_FEATURES, _, _) >> {
      savedFeaturesDeserializer = it[1]
      savedFeaturesListener = it[2]
      true
    }
    1 * poller.addCapabilities(14L)
    0 * _._
    initialWafConfig.get() != null
    initialWafData.present == false
    initialRulesOverride.present == false

    when:
    savedConfChangesListener.accept(
      'ignored config key',
      savedConfDeserializer.deserialize(
      '{"version": "2.0"}'.bytes), null)
    savedWafDataChangesListener.accept(
      'ignored config key',
      savedWafDataDeserializer.deserialize('{"rules_data":[{"id":"foo","type":"","data":[]}]}'.bytes), null
      )
    savedWafRulesOverrideListener.accept(
      'ignored config key',
      savedWafRulesOverrideDeserializer.deserialize('{"rules_override": [{"id": "foo", "enabled":false}]}'.bytes), null
      )
    savedFeaturesListener.accept(
      'ignored config key',
      savedFeaturesDeserializer.deserialize(
      '{"asm":{"enabled": true}}'.bytes), null)

    then:
    1 * subconfigListener.onNewSubconfig(AppSecConfig.valueOf([version: '2.0']), _)
    1 * wafDataListener.onNewSubconfig([[id: 'foo', type: '', data: []]], _)
    1 * wafRulesOverrideListener.onNewSubconfig([foo: false], _)
    0 * _._
    AppSecSystem.ACTIVE == true

    when:
    savedFeaturesListener.accept('config_key',
      savedFeaturesDeserializer.deserialize('{"asm":{"enabled": false}}'.bytes),
      ConfigurationChangesListener.PollingRateHinter.NOOP)

    then:
    AppSecSystem.ACTIVE == false

    cleanup:
    AppSecSystem.ACTIVE = true
  }

  void 'stopping appsec unsubscribes from the poller'() {
    setup:
    appSecConfigService.maybeSubscribeConfigPolling()

    when:
    appSecConfigService.close()
    poller = null

    then:
    1 * poller.removeCapabilities(14)
    4 * poller.removeListener(_)
    1 * poller.stop()
  }

  void 'error in one listener does not prevent others from running'() {
    AppSecModuleConfigurer.SubconfigListener fooListener = Mock()
    ConfigurationDeserializer<AppSecConfig> savedConfDeserializer
    ConfigurationChangesListener<AppSecConfig> savedConfChangesListener

    when:
    def configurer = appSecConfigService.createAppSecModuleConfigurer()
    configurer.addSubConfigListener("waf", { Object[] args ->
      throw new RuntimeException('bar')
    } as AppSecModuleConfigurer.SubconfigListener)
    configurer.addSubConfigListener("foo", fooListener)
    configurer.commit()
    appSecConfigService.init()
    appSecConfigService.maybeSubscribeConfigPolling()

    then:
    1 * poller.addListener(Product.ASM_DD, _, _) >> {
      savedConfDeserializer = it[1]
      savedConfChangesListener = it[2]
      true
    }

    when:
    savedConfChangesListener.accept(
      _ as String,
      savedConfDeserializer.deserialize(
      '{"version": "1.1"}, "foo": {"version": "2.0"}'.bytes), null)

    then:
    appSecConfigService.lastConfig['waf'].@version == '1.1'
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
