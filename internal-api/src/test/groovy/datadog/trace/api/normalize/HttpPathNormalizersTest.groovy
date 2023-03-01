package datadog.trace.api.normalize

import datadog.trace.api.Pair
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING

class HttpPathNormalizersTest extends DDSpecification {
  def setup() {
    HttpPathNormalizers.INSTANCE = null
  }

  def "uses the simple normalizer for servers by default"() {
    when:
    Pair<String, Byte> normalized = HttpPathNormalizers.serverChainWithPriority("/asdf/1234", false)

    then:
    normalized.getLeft() == "/asdf/?"
    normalized.getRight() == ResourceNamePriorities.HTTP_PATH_NORMALIZER
  }

  def "uses the ant matching normalizer for servers when configured to"() {
    setup:
    injectSysConfig(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, "/asdf/*:/test")

    when:
    Pair<String, Byte> normalized = HttpPathNormalizers.serverChainWithPriority("/asdf/1234", false)

    then:
    normalized.getLeft() == "/test"
    normalized.getRight() == ResourceNamePriorities.HTTP_SERVER_CONFIG_PATTERN_MATCH
  }

  def "uses the simple normalizer for clients by default"() {
    when:
    Pair<String, Byte> normalized = HttpPathNormalizers.clientChainWithPriority("/asdf/1234", false)

    then:
    normalized.getLeft() == "/asdf/?"
    normalized.getRight() == ResourceNamePriorities.HTTP_PATH_NORMALIZER
  }

  def "uses the ant matching normalizer for clients when configured to"() {
    setup:
    injectSysConfig(TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING, "/asdf/*:/test")

    when:
    Pair<String, Byte> normalized = HttpPathNormalizers.clientChainWithPriority("/asdf/1234", false)

    then:
    normalized.getLeft() == "/test"
    normalized.getRight() == ResourceNamePriorities.HTTP_CLIENT_CONFIG_PATTERN_MATCH
  }
}
