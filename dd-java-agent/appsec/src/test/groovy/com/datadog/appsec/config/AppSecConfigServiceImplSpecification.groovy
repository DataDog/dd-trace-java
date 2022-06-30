package com.datadog.appsec.config

import com.datadog.appsec.util.AbortStartupException
import datadog.remote_config.ConfigurationChangesListener
import datadog.remote_config.ConfigurationDeserializer
import datadog.remote_config.ConfigurationPoller
import datadog.remote_config.Product
import datadog.trace.test.util.DDSpecification

import java.nio.file.Files
import java.nio.file.Path

class AppSecConfigServiceImplSpecification extends DDSpecification {

  ConfigurationPoller poller = Mock()
  def config = Mock(Class.forName('datadog.trace.api.Config'))
  AppSecConfigServiceImpl appSecConfigService = new AppSecConfigServiceImpl(config, poller)

  void cleanup() {
    appSecConfigService.close()
  }

  void 'init subscribes to the configuration poller'() {
    when:
    appSecConfigService.init()

    then:
    1 * poller.addListener(Product.ASM_DD, _, _)
    1 * poller.addFeaturesListener('asm', _, _)
  }

  void 'can load from a different location'() {
    setup:
    Path p = Files.createTempFile('appsec', '.json')
    p.toFile() << '{"version":"2.0", "rules": []}'
    AppSecConfigService.SubconfigListener listener = Mock()

    when:
    appSecConfigService.init()

    then:
    1 * config.getAppSecRulesFile() >> (p as String)
    def expected = AppSecConfig.valueOf([version: '2.0', rules: []])
    def actual = appSecConfigService.addSubConfigListener('waf', listener).get()
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
    AppSecConfigService.SubconfigListener listener = Mock()

    setup:
    appSecConfigService.init()

    expect:
    appSecConfigService.addSubConfigListener("waf", listener).get() instanceof AppSecConfig
    appSecConfigService.addSubConfigListener("waf2", listener) == Optional.empty()
  }

  void 'provides updated configuration to waf subscription'() {
    AppSecConfigService.SubconfigListener subconfigListener = Mock()
    ConfigurationDeserializer<AppSecConfig> savedConfDeserializer
    ConfigurationChangesListener<AppSecConfig> savedConfChangesListener
    ConfigurationDeserializer<AppSecFeatures> savedFeaturesDeserializer
    ConfigurationChangesListener<AppSecFeatures> savedFeaturesListener
    def initialWafConfig

    when:
    appSecConfigService.init()
    initialWafConfig = appSecConfigService.addSubConfigListener("waf", subconfigListener)

    then:
    1 * poller.addListener(Product.ASM_DD, _, _) >> {
      savedConfDeserializer = it[1]
      savedConfChangesListener = it[2]
      true
    }
    1 * poller.addFeaturesListener('asm', _, _) >> {
      savedFeaturesDeserializer = it[1]
      savedFeaturesListener = it[2]
      true
    }
    initialWafConfig.get() != null

    when:
    savedConfChangesListener.accept(
      savedConfDeserializer.deserialize(
      '{"version": "2.0"}, "foo": {"version": "1.0"}'.bytes), null)
    savedFeaturesListener.accept(
      savedFeaturesDeserializer.deserialize(
      '{"enabled": true}'.bytes), null)

    then:
    1 * subconfigListener.onNewSubconfig(AppSecConfig.valueOf([version: '2.0']))
  }

  void 'error in one listener does not prevent others from running'() {
    AppSecConfigService.SubconfigListener fooListener = Mock()
    ConfigurationDeserializer<AppSecConfig> savedConfDeserializer
    ConfigurationChangesListener<AppSecConfig> savedConfChangesListener

    when:
    appSecConfigService.addSubConfigListener("waf", {
      throw new RuntimeException('bar')
    } as AppSecConfigService.SubconfigListener)
    appSecConfigService.addSubConfigListener("foo", fooListener)
    appSecConfigService.init()

    then:
    1 * poller.addListener(Product.ASM_DD, _, _) >> {
      savedConfDeserializer = it[1]
      savedConfChangesListener = it[2]
      true
    }

    when:
    savedConfChangesListener.accept(
      savedConfDeserializer.deserialize(
      '{"version": "1.1"}, "foo": {"version": "2.0"}'.bytes), null)

    then:
    appSecConfigService.lastConfig.get()['waf'].@version == '1.1'
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
