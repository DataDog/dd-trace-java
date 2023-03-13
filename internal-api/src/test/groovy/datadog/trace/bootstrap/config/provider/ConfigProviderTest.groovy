package datadog.trace.bootstrap.config.provider

import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING

class ConfigProviderTest extends DDSpecification {

  @Shared
  ConfigProvider configProvider = ConfigProvider.withoutCollector()

  def "properties take precedence over env vars for ordered map"() {
    setup:
    injectEnvConfig("TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING", "/a:env,/b:env")
    injectSysConfig(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, "/a:prop")

    when:
    def config = configProvider.getOrderedMap(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING)

    then:
    config["/a"] == "prop"
    config["/b"] == "env"
  }
}
