package datadog.trace.core.tagprocessor

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

class HttpEndpointTaggingConfigTest extends DDSpecification {

  def "should be disabled by default"() {
    expect:
    !Config.get().isResourceRenamingEnabled()
  }

  def "should be enabled with DD_TRACE_RESOURCE_RENAMING_ENABLED=true"() {
    setup:
    injectSysConfig("dd.trace.resource.renaming.enabled", "true")

    expect:
    Config.get().isResourceRenamingEnabled()
  }

  def "should be disabled with DD_TRACE_RESOURCE_RENAMING_ENABLED=false"() {
    setup:
    injectSysConfig("dd.trace.resource.renaming.enabled", "false")

    expect:
    !Config.get().isResourceRenamingEnabled()
  }

  def "should support simplified endpoint override with DD_TRACE_RESOURCE_RENAMING_ALWAYS_SIMPLIFIED_ENDPOINT=true"() {
    setup:
    injectSysConfig("dd.trace.resource.renaming.always-simplified-endpoint", "true")

    expect:
    Config.get().isResourceRenamingAlwaysSimplifiedEndpoint()
  }

  def "should support simplified endpoint override with DD_TRACE_RESOURCE_RENAMING_ALWAYS_SIMPLIFIED_ENDPOINT=1"() {
    setup:
    injectSysConfig("dd.trace.resource.renaming.always-simplified-endpoint", "1")

    expect:
    Config.get().isResourceRenamingAlwaysSimplifiedEndpoint()
  }

  def "should not enable simplified endpoint override by default"() {
    expect:
    !Config.get().isResourceRenamingAlwaysSimplifiedEndpoint()
  }
}
