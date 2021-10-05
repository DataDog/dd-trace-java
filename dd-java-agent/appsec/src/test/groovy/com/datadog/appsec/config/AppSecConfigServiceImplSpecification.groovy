package com.datadog.appsec.config

import com.datadog.appsec.util.AbortStartupException
import datadog.communication.fleet.FleetService
import datadog.communication.fleet.FleetServiceImpl
import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

import java.nio.file.Files
import java.nio.file.Path

class AppSecConfigServiceImplSpecification extends DDSpecification {

  FleetServiceImpl fleetService = Mock()
  Config config = Mock()
  AppSecConfigServiceImpl appSecConfigService = new AppSecConfigServiceImpl(config, fleetService)

  void cleanup() {
    appSecConfigService.close()
  }

  void 'init subscribes to the fleet service if given true as 2nd argument'() {
    when:
    appSecConfigService.init(true)

    then:
    1 * fleetService.subscribe(FleetService.Product.APPSEC, _)
  }

  void 'can load from a different location'() {
    setup:
    Path p = Files.createTempFile('appsec', '.json')
    p.toFile() << '{"waf": {"version":"0.1", "events": []}}'
    AppSecConfigService.SubconfigListener listener = Mock()

    when:
    appSecConfigService.init(false)

    then:
    1 * config.getAppSecRulesFile() >> (p as String)
    def expected = AppSecConfig.createFromMap([version: '0.1', events: []])
    def actual = appSecConfigService.addSubConfigListener('waf', listener).get()
    actual == expected
  }

  void 'aborts if alt config location does not exist'() {
    when:
    appSecConfigService.init(false)

    then:
    1 * config.getAppSecRulesFile() >> '/file/that/does/not/exist'
    thrown AbortStartupException
  }

  void 'aborts if alt config file is not valid json'() {
    setup:
    Path p = Files.createTempFile('appsec', '.json')
    p.toFile() << 'THIS IS NOT JSON'

    when:
    appSecConfigService.init(false)

    then:
    1 * config.getAppSecRulesFile() >> (p as String)
    thrown AbortStartupException
  }

  void 'provides initial subconfiguration upon subscription'() {
    AppSecConfigService.SubconfigListener listener = Mock()

    setup:
    appSecConfigService.init(false)

    expect:
    appSecConfigService.addSubConfigListener("waf", listener).get() instanceof AppSecConfig
    appSecConfigService.addSubConfigListener("waf2", listener) == Optional.empty()
  }

  void 'provides updated configuration to subscription'() {
    AppSecConfigService.SubconfigListener subconfigListener = Mock()
    FleetService.ConfigurationListener savedConfigurationListener
    def initialWafConfig

    when:
    appSecConfigService.init(true)
    initialWafConfig = appSecConfigService.addSubConfigListener("waf", subconfigListener)

    then:
    1 * fleetService.subscribe(FleetService.Product.APPSEC, _) >> {
      savedConfigurationListener = it[1]
      Mock(FleetService.FleetSubscription)
    }
    initialWafConfig.get() != null

    when:
    savedConfigurationListener.onNewConfiguration(
      new ByteArrayInputStream(
      '{"waf": {"version": "my config"}, "foo": {"version": "another config"}}'.bytes))

    then:
    1 * subconfigListener.onNewSubconfig(AppSecConfig.createFromMap([version: 'my config']))

    when:
    def fooInitialConfig = appSecConfigService.addSubConfigListener('foo', Mock(AppSecConfigService.SubconfigListener))

    then:
    fooInitialConfig.get() == AppSecConfig.createFromMap([version: 'another config'])
  }

  void 'error in one listener does not prevent others from running'() {
    AppSecConfigService.SubconfigListener fooListener = Mock()
    FleetService.ConfigurationListener savedConfigurationListener

    when:
    appSecConfigService.addSubConfigListener("waf", {
      throw new RuntimeException('bar')
    } as AppSecConfigService.SubconfigListener)
    appSecConfigService.addSubConfigListener("foo", fooListener)
    appSecConfigService.init(true)

    then:
    1 * fleetService.subscribe(FleetService.Product.APPSEC, _) >> {
      savedConfigurationListener = it[1]
      Mock(FleetService.FleetSubscription)
    }

    when:
    savedConfigurationListener.onNewConfiguration(new ByteArrayInputStream(
      '{"waf": {"version": "waf waf"}, "foo": {"version": "bar"}}'.bytes))

    then:
    1 * fooListener.onNewSubconfig(AppSecConfig.createFromMap([version: 'bar']))
  }

  void 'config should not be created'() {
    def conf

    when:
    conf = AppSecConfig.createFromMap(null)

    then:
    conf == null
  }
}
