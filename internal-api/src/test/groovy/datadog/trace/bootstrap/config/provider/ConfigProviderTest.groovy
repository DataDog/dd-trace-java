package datadog.trace.bootstrap.config.provider

import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING

class ConfigProviderTest extends DDSpecification {

  static final String PREFIX = "dd."

  def "verify properties precedence for ordered map"() {
    setup:
    injectEnvConfig("TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING", "/a:env,/b:env")
    injectSysConfig(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, "/a:prop")

    Properties agentArgs = new Properties()
    agentArgs.put(PREFIX + TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, "/a:arg,/b:arg,/c:arg")
    AgentArgsConfigSource.agentArgs = agentArgs

    when:
    def configProvider = ConfigProvider.withoutCollector()
    def config = configProvider.getOrderedMap(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING)

    then:
    config["/a"] == "prop"
    config["/b"] == "env"
    config["/c"] == "arg"
  }
}
