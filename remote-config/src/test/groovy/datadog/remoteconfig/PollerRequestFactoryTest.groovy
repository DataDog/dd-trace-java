package datadog.remoteconfig

import datadog.remoteconfig.tuf.RemoteConfigRequest
import datadog.trace.test.util.DDSpecification
import datadog.trace.api.Config

class PollerRequestFactoryTest extends DDSpecification {

  static final String TRACER_VERSION = "v1.2.3"
  static final String CONTAINER_ID = "456"
  static final String INVALID_REMOTE_CONFIG_URL = "https://invalid.example.com/"

  void 'remote config request fields been sanitized'() {
    given:
    System.setProperty("dd.service", "Service Name")
    System.setProperty("dd.env", "PROD")
    System.setProperty("dd.tags", "version:1.0.0-SNAPSHOT")
    rebuildConfig()
    PollerRequestFactory factory = new PollerRequestFactory(Config.get(), TRACER_VERSION, CONTAINER_ID, INVALID_REMOTE_CONFIG_URL, null)

    when:
    RemoteConfigRequest request = factory.buildRemoteConfigRequest( Collections.singletonList("ASM"), null, null, 0)

    then:
    request.client.tracerInfo.serviceName == "service_name"
    request.client.tracerInfo.serviceEnv == "prod"
    request.client.tracerInfo.serviceVersion == "1.0.0-snapshot"
  }
}
