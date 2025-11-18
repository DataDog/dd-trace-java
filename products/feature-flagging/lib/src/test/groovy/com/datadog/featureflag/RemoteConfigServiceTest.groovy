package com.datadog.featureflag

import com.squareup.moshi.JsonReader
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.remoteconfig.Capabilities
import datadog.remoteconfig.ConfigurationDeserializer
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.PollingRateHinter
import datadog.remoteconfig.Product
import datadog.trace.api.Config
import datadog.trace.api.featureflag.FeatureFlaggingGateway
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration
import datadog.trace.test.util.DDSpecification

class RemoteConfigServiceTest extends DDSpecification {

  private FeatureFlaggingGateway.ConfigListener listener

  void setup() {
    listener = Mock(FeatureFlaggingGateway.ConfigListener)
  }

  void cleanup() {
    FeatureFlaggingGateway.removeConfigListener(listener)
  }

  void 'test new config received'() {
    setup:
    def poller = Mock(ConfigurationPoller)
    final sco = Stub(SharedCommunicationObjects) {
      configurationPoller(_ as Config) >> poller
    }
    FeatureFlaggingGateway.addConfigListener(listener)
    final service = new RemoteConfigServiceImpl(sco, Config.get())
    final config = """
{
   "createdAt":"2024-04-17T19:40:53.716Z",
   "format":"SERVER",
   "environment":{
      "name":"Test"
   },
   "flags":{
      
   }
}
""".bytes
    ConfigurationDeserializer<ServerConfiguration> deserializer = null

    when:
    service.init()

    then:
    1 * poller.addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES)
    1 * poller.addListener(Product.FFE_FLAGS, _ as ConfigurationDeserializer, _) >> {
      deserializer = it[1] as ConfigurationDeserializer<ServerConfiguration>
    }

    when:
    service.accept('test', deserializer.deserialize(config), Mock(PollingRateHinter))

    then:
    1 * listener.accept(_ as ServerConfiguration)

    when:
    service.close()

    then:
    1 * poller.removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES)
    1 * poller.removeListeners(Product.FFE_FLAGS)

    cleanup:
    FeatureFlaggingGateway.removeConfigListener(listener)
  }

  void 'test date parsing'() {
    given:
    final reader = Stub(JsonReader) {
      nextString() >> string
    }
    final adapter = new RemoteConfigServiceImpl.DateAdapter()

    when:
    final date = adapter.fromJson(reader)

    then:
    date == expected

    where:
    string                      | expected
    // Valid ISO 8601 formats
    "2023-01-01T00:00:00Z"      | new Date(1672531200000L) // 2023-01-01 00:00:00 UTC
    "2023-12-31T23:59:59Z"      | new Date(1704067199000L) // 2023-12-31 23:59:59 UTC
    "2024-02-29T12:00:00Z"      | new Date(1709208000000L) // Leap year date
    "2023-01-01T00:00:00.000Z"  | new Date(1672531200000L) // With milliseconds
    "2023-06-15T14:30:45.123Z"  | new Date(1686839445123L) // With milliseconds
    // Non supported formats should return null
    "2023-01-01T01:00:00+01:00" | null // UTC+1
    "2023-01-01T00:00:00-05:00" | null // UTC-5
    "2023-01-01"                | null // Date only
    "invalid-date"              | null
    ""                          | null
    "not-a-date"                | null
    "2023/01/01T00:00:00Z"      | null // Wrong separator

    // Null input
    null                        | null
  }
}
