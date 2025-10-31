package com.datadog.featureflag

import static com.datadog.featureflag.utils.TestUtils.fetchConfiguration

import com.datadog.featureflag.ufc.v1.ServerConfiguration
import datadog.remoteconfig.ConfigurationDeserializer
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.PollingRateHinter
import datadog.remoteconfig.Product
import datadog.trace.api.featureflag.FeatureFlag
import datadog.trace.api.featureflag.FeatureFlagConfigListener
import datadog.trace.test.util.DDSpecification

class FeatureFlagRemoteConfigServiceTest extends DDSpecification {

  private FeatureFlagConfigListener listener

  void setup() {
    listener = Mock(FeatureFlagConfigListener)
  }

  void cleanup() {
    FeatureFlag.removeListener(listener)
  }

  void 'test new config received'() {
    setup:
    final poller = Mock(ConfigurationPoller)
    FeatureFlag.addListener(listener)
    final service = new FeatureFlagRemoteConfigServiceImpl(poller)
    final config = fetchConfiguration( 'data/flags-v1.json').bytes
    ConfigurationDeserializer<ServerConfiguration> deserializer = null

    when:
    service.init()

    then:
    1 * poller.addListener(Product.FFE_FLAGS, _ as ConfigurationDeserializer, _) >> {
      deserializer = it[1]
    }

    when:
    service.accept('test', deserializer.deserialize(config), Mock(PollingRateHinter))

    then:
    1 * listener.onConfigurationChanged({
      ServerConfiguration ufc ->
      ufc.createdAt == '2024-04-17T19:40:53.716Z'
      && ufc.format == 'SERVER'
      && ufc.environment.name == 'Test'
      && ufc.flags.empty_flag.key == 'empty_flag'
    })
  }
}
