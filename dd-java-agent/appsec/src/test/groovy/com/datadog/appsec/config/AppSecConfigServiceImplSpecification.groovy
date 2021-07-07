package com.datadog.appsec.config

import datadog.communication.fleet.FleetService
import datadog.communication.fleet.FleetServiceImpl
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AppSecConfigServiceImplSpecification extends Specification {

  FleetServiceImpl fleetService = Mock()
  AppSecConfigServiceImpl appSecConfigService = new AppSecConfigServiceImpl(fleetService)

  void teardown() {
    appSecConfigService.close()
  }

  void 'subscribes to appsec on a new thread'() {
    def threadName

    when:
    appSecConfigService.testingLatch = new CountDownLatch(1)
    appSecConfigService.init()
    await()

    then:
    1 * fleetService.subscribe(FleetService.Product.APPSEC, _ as FleetService.ConfigurationListener) >> {
      threadName = Thread.currentThread().name
    }
    threadName == 'appsec_config'
  }

  void 'provides initial subconfiguration upon subscription'() {
    AppSecConfigService.SubconfigListener listener = Mock()
    setup:
    appSecConfigService.init()

    expect:
    appSecConfigService.addSubConfigListener("waf", listener).get() instanceof Map
    appSecConfigService.addSubConfigListener("waf2", listener) == Optional.empty()
  }

  void 'provides update configuration to subscription'() {
    AppSecConfigService.SubconfigListener subconfigListener = Mock()
    FleetService.ConfigurationListener savedConfigurationListener

    when:
    appSecConfigService.testingLatch = new CountDownLatch(1)
    appSecConfigService.addSubConfigListener("waf", subconfigListener)
    appSecConfigService.init()
    await()

    then:
    1 * fleetService.subscribe(FleetService.Product.APPSEC, _) >> {
      savedConfigurationListener = it[1]
    }

    when:
    appSecConfigService.testingLatch = new CountDownLatch(1)
    savedConfigurationListener.onNewConfiguration(new ByteArrayInputStream('{"waf": "my config"}'.bytes))
    await()

    then:
    1 * subconfigListener.onNewSubconfig('my config')
  }

  void 'error in one listener does not prevent others from running'() {
    AppSecConfigService.SubconfigListener fooListener = Mock()
    FleetService.ConfigurationListener savedConfigurationListener

    when:
    appSecConfigService.testingLatch = new CountDownLatch(1)
    appSecConfigService.addSubConfigListener("waf", {
      throw new RuntimeException('bar')
    } as AppSecConfigService.SubconfigListener)
    appSecConfigService.addSubConfigListener("foo", fooListener)
    appSecConfigService.init()
    await()

    then:
    1 * fleetService.subscribe(FleetService.Product.APPSEC, _) >> {
      savedConfigurationListener = it[1]
    }

    when:
    appSecConfigService.testingLatch = new CountDownLatch(1)
    savedConfigurationListener.onNewConfiguration(new ByteArrayInputStream(
      '{"waf": "waf waf", "foo": "bar"}'.bytes))
    await()

    then:
    1 * fooListener.onNewSubconfig('bar')
  }

  void 'a completion event stops the service'() {
    AppSecConfigService.SubconfigListener subconfigListener = Mock()
    FleetService.ConfigurationListener savedConfigurationListener

    when:
    appSecConfigService.testingLatch = new CountDownLatch(1)
    appSecConfigService.addSubConfigListener("waf", subconfigListener)
    appSecConfigService.init()
    await()

    then:
    1 * fleetService.subscribe(FleetService.Product.APPSEC, _) >> {
      savedConfigurationListener = it[1]
    }

    when:
    appSecConfigService.testingLatch = new CountDownLatch(1)
    savedConfigurationListener.onCompleted()
    await()
    appSecConfigService.thread.join(2000)

    then:
    appSecConfigService.thread.alive == false
  }

  void 'an error event triggers another subscription'() {
    AppSecConfigService.SubconfigListener subconfigListener = Mock()
    FleetService.ConfigurationListener savedConfigurationListener

    when:
    appSecConfigService.testingLatch = new CountDownLatch(1)
    appSecConfigService.addSubConfigListener("waf", subconfigListener)
    appSecConfigService.init()
    await()

    then:
    1 * fleetService.subscribe(FleetService.Product.APPSEC, _) >> {
      savedConfigurationListener = it[1]
    }

    when:
    appSecConfigService.testingLatch = new CountDownLatch(1)
    savedConfigurationListener.onError(new Throwable())
    await()

    then:
    appSecConfigService.thread.alive == true
    1 * fleetService.subscribe(FleetService.Product.APPSEC, _)
  }

  private void await() {
    if (!appSecConfigService.testingLatch.await(5, TimeUnit.SECONDS)) {
      throw new TimeoutException("await timed out")
    }
  }
}
