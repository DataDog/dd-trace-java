package datadog.trace.bootstrap.config.provider

import datadog.trace.api.ConfigOrigin
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

  def "ConfigProvider handles ConfigSourceException gracefully"() {
    given:
    def throwingSource = new ConfigProvider.Source() {
        @Override
        protected String get(String key) throws ConfigSourceException {
          throw new ConfigSourceException("raw")
        }
        @Override
        ConfigOrigin origin() {
          ConfigOrigin.ENV
        }
      }
    // Create a provider with a collector
    def provider = new ConfigProvider(true, throwingSource)

    expect:
    //Any "get" method should return the default value, if provided
    provider.getString("any.key", "default") == "default"
    provider.getBoolean("any.key", true) == true
    provider.getInteger("any.key", 42) == 42
    provider.getLong("any.key", 123L) == 123L
    provider.getFloat("any.key", 1.23f) == 1.23f
    provider.getDouble("any.key", 2.34d) == 2.34d
    provider.getList("any.key", ["a", "b"]) == ["a", "b"]
    provider.getSet("any.key", ["x", "y"] as Set) == ["x", "y"] as Set
  }

  def "ConfigProvider skips sources that throw ConfigSourceException and uses next available value"() {
    given:
    def throwingSource = new ConfigProvider.Source() {
        @Override
        protected String get(String key) throws ConfigSourceException {
          throw new ConfigSourceException("raw")
        }
        @Override
        ConfigOrigin origin() {
          ConfigOrigin.ENV
        }
      }

    def workingSource = new ConfigProvider.Source() {
        @Override
        protected String get(String key) throws ConfigSourceException {
          if (key == "any.key") {
            return "fromSecondSource"
          }
          return null
        }
        @Override
        ConfigOrigin origin() {
          ConfigOrigin.JVM_PROP
        }
      }
    def provider = new ConfigProvider(true, throwingSource, workingSource)

    expect:
    provider.getString("any.key", "default") == "fromSecondSource"
  }
}
