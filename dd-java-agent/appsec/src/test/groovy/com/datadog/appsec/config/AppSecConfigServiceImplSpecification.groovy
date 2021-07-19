package com.datadog.appsec.config

import datadog.communication.fleet.FleetService
import datadog.communication.fleet.FleetServiceImpl
import spock.lang.Specification

class AppSecConfigServiceImplSpecification extends Specification {

  FleetServiceImpl fleetService = Mock()
  AppSecConfigServiceImpl appSecConfigService = new AppSecConfigServiceImpl(fleetService)

  void cleanup() {
    appSecConfigService.close()
  }

  void 'init subscribes to the fleet service'() {
    when:
    appSecConfigService.init()

    then:
    1 * fleetService.subscribe(FleetService.Product.APPSEC, _)
  }

  void 'provides initial subconfiguration upon subscription'() {
    AppSecConfigService.SubconfigListener listener = Mock()

    expect:
    appSecConfigService.addSubConfigListener("waf", listener).get() instanceof Map
    appSecConfigService.addSubConfigListener("waf2", listener) == Optional.empty()
  }

  void 'provides update configuration to subscription'() {
    AppSecConfigService.SubconfigListener subconfigListener = Mock()
    FleetService.ConfigurationListener savedConfigurationListener

    when:
    appSecConfigService.addSubConfigListener("waf", subconfigListener)
    appSecConfigService.init()

    then:
    1 * fleetService.subscribe(FleetService.Product.APPSEC, _) >> {
      savedConfigurationListener = it[1]
      Mock(FleetService.FleetSubscription)
    }

    when:
    savedConfigurationListener.onNewConfiguration(new ByteArrayInputStream('{"waf": "my config"}'.bytes))

    then:
    1 * subconfigListener.onNewSubconfig('my config')
  }

  void 'error in one listener does not prevent others from running'() {
    AppSecConfigService.SubconfigListener fooListener = Mock()
    FleetService.ConfigurationListener savedConfigurationListener

    when:
    appSecConfigService.addSubConfigListener("waf", {
      throw new RuntimeException('bar')
    } as AppSecConfigService.SubconfigListener)
    appSecConfigService.addSubConfigListener("foo", fooListener)
    appSecConfigService.init()

    then:
    1 * fleetService.subscribe(FleetService.Product.APPSEC, _) >> {
      savedConfigurationListener = it[1]
      Mock(FleetService.FleetSubscription)
    }

    when:
    savedConfigurationListener.onNewConfiguration(new ByteArrayInputStream(
      '{"waf": "waf waf", "foo": "bar"}'.bytes))

    then:
    1 * fooListener.onNewSubconfig('bar')
  }
}
