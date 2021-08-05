package com.datadog.appsec

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.monitor.Monitoring
import datadog.trace.api.gateway.SubscriptionService
import okhttp3.OkHttpClient
import spock.lang.Specification

class AppSecSystemSpecification extends Specification {
  SubscriptionService subService = Mock()

  def cleanup() {
    AppSecSystem.stop()
  }

  void 'registers powerwaf module'() {
    def objects = new SharedCommunicationObjects()
    objects.okHttpClient = Mock(OkHttpClient)
    objects.monitoring = Mock(Monitoring)
    objects.featuresDiscovery = Mock(DDAgentFeaturesDiscovery)

    when:
    AppSecSystem.start(subService, objects)

    then:
    'powerwaf' in AppSecSystem.startedModuleNames
  }

  void 'when not started returns empty list for started modules'() {
    expect:
    AppSecSystem.startedModuleNames.empty
  }
}
