package datadog.trace.util

import datadog.trace.api.Config
import datadog.trace.api.remoteconfig.ServiceNameCollector
import spock.lang.Specification

class ServiceNameCollectorTest extends Specification {


  void 'test add extra service'(){
    given:
    final provider = new ServiceNameCollector()
    final service = 'testService'

    when:
    provider.addService(service)

    then:
    provider.getServices()[0] == service
  }

  void 'test add invalid extra service'(){
    given:
    final provider = new ServiceNameCollector()

    when:
    provider.addService(value)

    then:
    provider.getServices() == null

    where:
    value | _
    null | _
    '' | _
  }

  void 'Extra service is not added if it is the default one'(){
    given:
    final provider = new ServiceNameCollector()
    final global = Config.get().getServiceName()

    when:
    provider.addService(global)

    then:
    global != null
    provider.getServices() == null
  }

  void 'Extra service is not added if already exist'(){
    given:
    final provider = new ServiceNameCollector()
    provider.addService('testService')
    assert  provider.getServices().size() == 1

    when:
    provider.addService(service)

    then:
    provider.getServices().size() == 1

    where:
    service | _
    'testService' | _
    'TestService' | _
  }

  void 'Extra service can not exceed 64 elements'(){
    given:
    final provider = new ServiceNameCollector()
    assert !provider.limitReachedLogged
    (0..64).each {provider.addService('testService'+it)}
    assert  provider.getServices().size() == 64
    assert provider.limitReachedLogged

    when:
    provider.addService('testService')

    then:
    provider.getServices().size() == 64
  }

  void 'test getExtraServices returns null if there are no extra services'(){
    given:
    final provider = new ServiceNameCollector()

    when:
    def result = provider.getServices()

    then:
    result == null
  }
}
