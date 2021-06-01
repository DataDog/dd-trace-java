package com.datadog.appsec


import datadog.trace.api.gateway.SubscriptionService
import spock.lang.Specification

class AppSecSystemSpecification extends Specification {
  SubscriptionService subService = Mock()

  void 'registers powerwaf module'() {
    when:
    AppSecSystem.start(subService)

    then:
    'powerwaf' in AppSecSystem.startedModuleNames
  }
}
