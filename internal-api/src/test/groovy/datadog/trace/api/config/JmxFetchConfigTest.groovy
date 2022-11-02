package datadog.trace.api.config

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_METRICS_CONFIGS
import static datadog.trace.api.ConfigTest.PREFIX

class JmxFetchConfigTest extends DDSpecification {


  def "verify empty value list configs on tracer"() {
    setup:
    System.setProperty(PREFIX + JMX_FETCH_METRICS_CONFIGS, listString)

    when:
    def config = new Config()

    then:
    config.jmxFetchMetricsConfigs == list

    where:
    // spotless:off
    listString | list
    ""         | []
    // spotless:on
  }
}
