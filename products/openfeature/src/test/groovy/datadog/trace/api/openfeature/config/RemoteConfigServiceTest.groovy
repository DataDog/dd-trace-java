package datadog.trace.api.openfeature.config

import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.remoteconfig.ConfigurationDeserializer
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.PollingRateHinter
import datadog.remoteconfig.Product
import datadog.trace.api.Config
import datadog.trace.api.openfeature.config.ufc.v1.ServerConfiguration
import datadog.trace.test.util.DDSpecification

class RemoteConfigServiceTest extends DDSpecification {

  private ServerConfigurationListener listener

  void setup() {
    listener = Mock(ServerConfigurationListener)
  }

  void 'test new config received'() {
    setup:
    def poller = Mock(ConfigurationPoller)
    final sco = Mock(SharedCommunicationObjects) {
      configurationPoller(_ as Config) >> poller
    }

    final service = new RemoteConfigServiceImpl(sco, Config.get(), listener)
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
    1 * poller.addListener(Product.FFE_FLAGS, _ as ConfigurationDeserializer, _) >> {
      deserializer = it[1] as ConfigurationDeserializer<ServerConfiguration>
    }

    when:
    service.accept('test', deserializer.deserialize(config), Mock(PollingRateHinter))

    then:
    1 * listener.onConfiguration(_ as ServerConfiguration)

    when:
    service.close()

    then:
    1 * poller.stop()
  }
}
