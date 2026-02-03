package com.datadog.featureflag

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.http.client.HttpUrl
import datadog.remoteconfig.Capabilities
import datadog.remoteconfig.ConfigurationDeserializer
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.Product
import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

class FeatureFlaggingSystemTest extends DDSpecification {

  void 'test feature flag system initialization'() {
    setup:
    final poller = Mock(ConfigurationPoller)
    final discovery = Stub(DDAgentFeaturesDiscovery) {
      discoverIfOutdated() >> {}
      supportsEvpProxy() >> { return true }
    }
    final sco = Stub(SharedCommunicationObjects) {
      configurationPoller(_ as Config) >> poller
      featuresDiscovery(_ as Config) >> discovery
    }
    sco.featuresDiscovery = discovery
    sco.agentUrl = HttpUrl.parse('http://localhost')

    when:
    FeatureFlaggingSystem.start(sco)

    then:
    1 * poller.addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES)
    1 * poller.addListener(Product.FFE_FLAGS, _ as ConfigurationDeserializer, _)
    1 * poller.start()

    when:
    FeatureFlaggingSystem.stop()

    then:
    1 * poller.removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES)
    1 * poller.removeListeners(Product.FFE_FLAGS)
    1 * poller.stop()
  }

  void 'test that remote config is required'() {
    setup:
    injectSysConfig('remote_configuration.enabled', 'false')
    final sco = Mock(SharedCommunicationObjects)

    when:
    FeatureFlaggingSystem.start(sco)

    then:
    thrown(IllegalStateException)

    cleanup:
    FeatureFlaggingSystem.stop()
  }
}
