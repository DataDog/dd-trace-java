package datadog.trace.api

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification
import spock.lang.Unroll

class ClassloaderConfigurationOverridesTest extends DDSpecification {

  @Unroll
  def 'service name set = #expected when split-by-deployment enabled is #splitByDeploymentEnabled and service name manually set = #spanServiceName and contextual service name is #contextualServiceName'() {
    setup:
    injectSysConfig("dd.service", "test")
    def span = Mock(AgentSpan)
    when:
    ClassloaderConfigurationOverrides.CAN_SPLIT_SERVICE_NAME_BY_DEPLOYMENT = splitByDeploymentEnabled
    if (splitByDeploymentEnabled) {
      injectSysConfig("dd.trace.experimental.jee.split-by-deployment", "true")
    }
    ClassloaderConfigurationOverrides.addContextualInfo(Thread.currentThread().getContextClassLoader(),
      new ClassloaderConfigurationOverrides.ContextualInfo(contextualServiceName))
    ClassloaderConfigurationOverrides.maybeEnrichSpan(span)
    then:
    if (splitByDeploymentEnabled && contextualServiceName != null && !contextualServiceName.isEmpty()) {
      (1.._) * span.getServiceName()
    }
    if (expected) {
      1 * span.setServiceName(contextualServiceName)
    }

    where:
    splitByDeploymentEnabled | contextualServiceName | spanServiceName | expected
    false                    | null                  | null            | false
    false                    | "test"                | null            | false
    false                    | null                  | "test"          | false
    true                     | null                  | null            | false
    true                     | ""                    | null            | false
    true                     | "cont"                | null            | true
    true                     | ""                    | "test"          | false
    true                     | null                  | "test"          | false
    true                     | "test"                | "test"          | false
    true                     | "cont"                | "test"          | true
  }

  def "enrich should set tags when present"() {
    def span = Mock(AgentSpan)
    when:
    ClassloaderConfigurationOverrides.getOrAddEmpty(Thread.currentThread().getContextClassLoader()).addTag("key", "value")
    ClassloaderConfigurationOverrides.maybeEnrichSpan(span)
    then:
    1 * span.setTag("key", "value")
    _ * span.getServiceName()
  }
}
