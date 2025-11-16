package com.datadog.featureflag

import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.remoteconfig.Capabilities
import datadog.remoteconfig.ConfigurationDeserializer
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.Product
import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification
import okhttp3.HttpUrl

class FeatureFlaggingSystemTest extends DDSpecification {

  void 'test feature flag system initialization'() {
    setup:
    final poller = Mock(ConfigurationPoller)
    final sco = Mock(SharedCommunicationObjects)
    sco.agentUrl = HttpUrl.get('http://localhost')

    when:
    FeatureFlaggingSystem.start(sco)

    then:
    1 * sco.configurationPoller(_ as Config) >> { poller }
    1 * poller.addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES)
    1 * poller.addListener(Product.FFE_FLAGS, _ as ConfigurationDeserializer, _)
    1 * poller.start()
    0 * _

    when:
    FeatureFlaggingSystem.stop()

    then:
    1 * poller.removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES)
    1 * poller.stop()
    0 * _
  }

  void 'test that a poller is required'() {
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
