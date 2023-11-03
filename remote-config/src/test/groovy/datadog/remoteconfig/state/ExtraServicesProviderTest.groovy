package datadog.remoteconfig.state

import datadog.trace.api.Config
import spock.lang.Specification

import static datadog.remoteconfig.state.ExtraServicesProvider.*

class ExtraServicesProviderTest extends Specification {

  def setup(){
    clear()
  }

  void 'test add extra service'(){
    given:
    final service = 'testService'

    when:
    maybeAddExtraService(service)

    then:
    getExtraServices()[0] == service
  }

  void 'test add null extra service'(){
    when:
    maybeAddExtraService(null)

    then:
    getExtraServices() == null
  }

  void 'Extra service is not added if it is the default one'(){
    given:
    final global = Config.get().getServiceName()

    when:
    maybeAddExtraService(global)

    then:
    global != null
    getExtraServices() == null
  }

  void 'Extra service is not added if already exist'(){
    given:
    maybeAddExtraService('testService')
    assert  getExtraServices().length == 1

    when:
    maybeAddExtraService(service)

    then:
    getExtraServices().length == 1

    where:
    service | _
    'testService' | _
    'TestService' | _
  }

  void 'Extra service can not exceed 64 elements'(){
    given:
    assert !limitReachedLogged
    (0..64).each {maybeAddExtraService('testService'+it)}
    assert  getExtraServices().length == 64
    assert limitReachedLogged

    when:
    maybeAddExtraService('testService')

    then:
    getExtraServices().length == 64
  }

  void 'test getExtraServices returns null if there are not extra services'(){
    when:
    def result = getExtraServices()

    then:
    result == null
  }
}
