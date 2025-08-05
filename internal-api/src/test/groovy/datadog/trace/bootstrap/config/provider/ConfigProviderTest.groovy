package datadog.trace.bootstrap.config.provider

import datadog.trace.test.util.DDSpecification
import spock.lang.Shared
import datadog.trace.api.ConfigCollector
import datadog.trace.api.ConfigOrigin

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


  def "test config alias priority"() {
    setup:
    injectEnvConfig("CONFIG_NAME", configNameValue)
    injectEnvConfig("CONFIG_ALIAS1", configAlias1Value)
    injectEnvConfig("CONFIG_ALIAS2", configAlias2Value)

    when:
    def config = configProvider.getString("CONFIG_NAME", null, "CONFIG_ALIAS1", "CONFIG_ALIAS2")

    then:
    config == expected

    where:
    configNameValue | configAlias1Value | configAlias2Value | expected
    "default"       | null              | null              | "default"
    null            | "alias1"          | null              | "alias1"
    null            | null              | "alias2"          | "alias2"
    "default"       | "alias1"          | null              | "default"
    "default"       | null              | "alias2"          | "default"
    null            | "alias1"          | "alias2"          | "alias1"
  }

  def "test always reports config in use with the highest seqId regardless of errors"() {
    setup:
    // System props is higher precedence than env config
    injectEnvConfig("CONFIG_NAME", "123")
    injectSysConfig("CONFIG_NAME", "not_an_int")

    when:
    def configProviderWithCollector = ConfigProvider.createDefault()
    def config = configProviderWithCollector.getInteger("CONFIG_NAME")

    then:
    config == 123

    when:
    def highestSeqIdConfig = ConfigCollector.get().getHighestSeqIdConfig('CONFIG_NAME')

    then:
    highestSeqIdConfig.key == 'CONFIG_NAME'
    highestSeqIdConfig.value == '123'
    highestSeqIdConfig.origin == ConfigOrigin.ENV
  }
}
