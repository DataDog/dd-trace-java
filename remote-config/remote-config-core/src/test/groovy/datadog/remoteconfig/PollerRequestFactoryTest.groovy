package datadog.remoteconfig

import com.squareup.moshi.Moshi
import datadog.remoteconfig.tuf.RemoteConfigRequest
import datadog.trace.api.ProcessTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.test.util.DDSpecification
import datadog.trace.api.Config
import datadog.trace.api.remoteconfig.ServiceNameCollector

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED

class PollerRequestFactoryTest extends DDSpecification {

  static final String TRACER_VERSION = "v1.2.3"
  static final String CONTAINER_ID = "456"
  static final String ENTITY_ID = "32423"
  static final String INVALID_REMOTE_CONFIG_URL = "https://invalid.example.com/"

  void 'remote config request fields been sanitized'() {
    given:
    System.setProperty("dd.service", "Service Name")
    System.setProperty("dd.env", "PROD")
    System.setProperty("dd.tags", "version:1.0.0-SNAPSHOT")
    System.setProperty("dd.trace.global.tags", Tags.GIT_REPOSITORY_URL+":https://github.com/DataDog/dd-trace-java,"+Tags.GIT_COMMIT_SHA + ":1234")
    rebuildConfig()
    PollerRequestFactory factory = new PollerRequestFactory(Config.get(), TRACER_VERSION, CONTAINER_ID, ENTITY_ID, INVALID_REMOTE_CONFIG_URL, null)

    when:
    RemoteConfigRequest request = factory.buildRemoteConfigRequest( Collections.singletonList("ASM"), null, null, 0, ServiceNameCollector.get())

    then:
    request.client.tracerInfo.serviceName == "service_name"
    request.client.tracerInfo.serviceEnv == "prod"
    request.client.tracerInfo.serviceVersion == "1.0.0-snapshot"
    request.client.tracerInfo.tags.contains("env:PROD")
    request.client.tracerInfo.tags.contains(Tags.GIT_REPOSITORY_URL + ":https://github.com/DataDog/dd-trace-java")
    request.client.tracerInfo.tags.contains(Tags.GIT_COMMIT_SHA + ":1234")
  }

  void 'remote config request extraServices'() {
    given:
    System.setProperty("dd.service", "Service Name")
    System.setProperty("dd.env", "PROD")
    System.setProperty("dd.tags", "version:1.0.0-SNAPSHOT")
    rebuildConfig()
    final extraService = 'fakeExtraService'
    ServiceNameCollector extraServicesProvider = new ServiceNameCollector()
    extraServicesProvider.addService(extraService)
    PollerRequestFactory factory = new PollerRequestFactory(Config.get(), TRACER_VERSION, CONTAINER_ID, ENTITY_ID, INVALID_REMOTE_CONFIG_URL, null)

    when:
    RemoteConfigRequest request = factory.buildRemoteConfigRequest( Collections.singletonList("ASM"), null, null, 0, extraServicesProvider)

    then:
    request.client.tracerInfo.extraServices.contains(extraService)
  }

  void 'remote config provides process tags when enabled  = #enabled'() {
    setup:
    if (!enabled) {
      injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false")
    }
    ProcessTags.reset()
    PollerRequestFactory factory = new PollerRequestFactory(Config.get(), TRACER_VERSION, CONTAINER_ID, ENTITY_ID, INVALID_REMOTE_CONFIG_URL, null)

    when:
    def request = factory.buildRemoteConfigRequest( Collections.singletonList("ASM"), null, null, 0, ServiceNameCollector.get())
    def json = new Moshi.Builder().build().adapter(RemoteConfigRequest).toJson(request)
    then:
    def epName = request.client.tracerInfo.processTags.find {it =~ "entrypoint.name:.+"}
    def workingDir = request.client.tracerInfo.processTags.find {it =~ "entrypoint.workdir:.+"}

    if (enabled) {
      assert workingDir != null
      assert epName != null
      assert json.contains('"process_tags":[')
    } else {
      assert workingDir == null
      assert epName == null
      assert !json.contains('"process_tags":[')
    }
    where:
    enabled << [true, false]
  }
}
