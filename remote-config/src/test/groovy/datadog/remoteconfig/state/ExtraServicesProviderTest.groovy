package datadog.remoteconfig.state

import datadog.trace.api.Config
import spock.lang.Specification

import static datadog.remoteconfig.state.ExtraServicesProvider.*

class ExtraServicesProviderTest extends Specification {


  void 'test add extra service'(){
    given:
    final provider = new ExtraServicesProvider()
    final service = 'testService'

    when:
    provider.maybeAddExtraService(service)

    then:
    provider.getExtraServices()[0] == service
  }

  void 'test add null extra service'(){
    given:
    final provider = new ExtraServicesProvider()

    when:
    provider.maybeAddExtraService(null)

    then:
    provider.getExtraServices() == null
  }

  void 'Extra service is not added if it is the default one'(){
    given:
    final provider = new ExtraServicesProvider()
    final global = Config.get().getServiceName()

    when:
    provider.maybeAddExtraService(global)

    then:
    global != null
    provider.getExtraServices() == null
  }

  void 'Extra service is not added if already exist'(){
    given:
    final provider = new ExtraServicesProvider()
    provider.maybeAddExtraService('testService')
    assert  provider.getExtraServices().size() == 1

    when:
    provider.maybeAddExtraService(service)

    then:
    provider.getExtraServices().size() == 1

    where:
    service | _
    'testService' | _
    'TestService' | _
  }

  void 'Extra service can not exceed 64 elements'(){
    given:
    final provider = new ExtraServicesProvider()
    assert !provider.limitReachedLogged
    (0..64).each {provider.maybeAddExtraService('testService'+it)}
    assert  provider.getExtraServices().size() == 64
    assert provider.limitReachedLogged

    when:
    provider.maybeAddExtraService('testService')

    then:
    provider.getExtraServices().size() == 64
  }

  void 'test getExtraServices returns null if there are no extra services'(){
    given:
    final provider = new ExtraServicesProvider()

    when:
    def result = provider.getExtraServices()

    then:
    result == null
  }
}
