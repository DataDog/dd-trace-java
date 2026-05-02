package datadog.trace.api

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared
import spock.lang.Unroll

class ClassloaderConfigurationOverridesTest extends DDSpecification {

  @Shared
  def ddService = Config.get().getServiceName()


  @Unroll
  def 'service name set = #expected when split-by-deployment enabled is #splitByDeploymentEnabled and service name manually set = #spanServiceName and contextual service name is #contextualServiceName'() {
    setup:
    def span = Mock(AgentSpan)
    when:
    ClassloaderConfigurationOverrides.CAN_SPLIT_SERVICE_NAME_BY_DEPLOYMENT = splitByDeploymentEnabled
    ClassloaderConfigurationOverrides.addContextualInfo(Thread.currentThread().getContextClassLoader(),
      new ClassloaderConfigurationOverrides.ContextualInfo(contextualServiceName, "test"))
    ClassloaderConfigurationOverrides.maybeEnrichSpan(span)
    then:
    if (splitByDeploymentEnabled && contextualServiceName != null && !contextualServiceName.isEmpty()) {
      (1.._) * span.getServiceName() >> spanServiceName
    }
    if (expected) {
      1 * span.setServiceName(contextualServiceName, "test")
    }

    where:
    splitByDeploymentEnabled | contextualServiceName | spanServiceName | expected
    false                    | null                  | null            | false
    false                    | "test"                | null            | false
    false                    | null                  | "test"          | false
    true                     | null                  | null            | false
    true                     | ""                    | null            | false
    true                     | "test"                | null            | true
    true                     | ""                    | "test"          | false
    true                     | null                  | "test"          | false
    true                     | "test"                | "test"          | false
    true                     | "test"                | ddService       | true
  }

  def "enrich should set tags when present"() {
    def span = Mock(AgentSpan)
    when:
    ClassloaderConfigurationOverrides.maybeCreateContextualInfo(Thread.currentThread().getContextClassLoader()).addTag("key", "value")
    ClassloaderConfigurationOverrides.maybeEnrichSpan(span)
    then:
    1 * span.setTag("key", "value")
    _ * span.getServiceName()
  }

  def "no enrichment when contextual info is absent"() {
    def span = Mock(AgentSpan)
    when:
    ClassloaderConfigurationOverrides.addContextualInfo(Thread.currentThread().getContextClassLoader(), null)
    then:
    ClassloaderConfigurationOverrides.maybeGetContextualInfo() == null
    when:
    ClassloaderConfigurationOverrides.maybeEnrichSpan(span)
    then:
    _
  }
}
