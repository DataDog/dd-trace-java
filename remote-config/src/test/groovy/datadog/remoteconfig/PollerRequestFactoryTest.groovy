package datadog.remoteconfig

import datadog.remoteconfig.tuf.RemoteConfigRequest
import datadog.trace.test.util.DDSpecification
import datadog.trace.api.Config

class PollerRequestFactoryTest extends DDSpecification {

  Config config = Mock()
  static final String TRACER_VERSION = "v1.2.3"
  static final String CONTAINER_ID = "456"

  void 'remote config request fields been sanitized'() {
    when:
    PollerRequestFactory factory = new PollerRequestFactory(config, TRACER_VERSION, CONTAINER_ID, null, null )

    then:
    1 * config.getServiceName() >> "Service Name"
    1 * config.getEnv() >> "PROD"
    1 * config.getVersion() >> "1.0.0-SNAPSHOT"

    when:
    RemoteConfigRequest request = factory.buildRemoteConfigRequest( Collections.singletonList("ASM"), null, null, 0)

    then:
    request.client.tracerInfo.serviceName == "service_name"
    request.client.tracerInfo.serviceEnv == "prod"
    request.client.tracerInfo.serviceVersion == "1.0.0-snapshot"
  }
}
