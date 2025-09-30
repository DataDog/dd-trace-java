package datadog.trace.api.rum

import spock.lang.Specification
import spock.lang.Subject

class RumInjectorMetricsTest extends Specification {

  @Subject
  def metrics = new RumInjectorMetrics()

  // Note: application_id and remote_config_used tags need dynamic runtime values that depend on
  // the RUM configuration state, so we do not test them here.
  def "test onInjectionSucceed"() {
    when:
    metrics.onInjectionSucceed("3")
    metrics.onInjectionSucceed("5")
    metrics.onInjectionSucceed("6")

    then:
    def drained = metrics.drain()
    drained.size() == 3

    def servlet3 = drained[0]
    servlet3.namespace == "rum"
    servlet3.metricName == "injection.succeed"
    servlet3.type == "count"
    servlet3.value == 1
    servlet3.common == true
    servlet3.tags.contains("integration_name:servlet")
    servlet3.tags.contains("integration_version:3")

    def servlet5 = drained[1]
    servlet5.namespace == "rum"
    servlet5.metricName == "injection.succeed"
    servlet5.type == "count"
    servlet5.value == 1
    servlet5.common == true
    servlet5.tags.contains("integration_name:servlet")
    servlet5.tags.contains("integration_version:5")

    def servlet6 = drained[2]
    servlet6.namespace == "rum"
    servlet6.metricName == "injection.succeed"
    servlet6.type == "count"
    servlet6.value == 1
    servlet6.common == true
    servlet6.tags.contains("integration_name:servlet")
    servlet6.tags.contains("integration_version:6")
  }

  def "test onInjectionFailed"() {
    when:
    metrics.onInjectionFailed("3", "gzip")
    metrics.onInjectionFailed("5", null)
    metrics.onInjectionFailed("6", "gzip")

    then:
    def drained = metrics.drain()
    drained.size() == 3

    def servlet3 = drained[0]
    servlet3.namespace == "rum"
    servlet3.metricName == "injection.failed"
    servlet3.type == "count"
    servlet3.value == 1
    servlet3.common == true
    servlet3.tags.contains("content_encoding:gzip")
    servlet3.tags.contains("integration_name:servlet")
    servlet3.tags.contains("integration_version:3")
    servlet3.tags.contains("reason:failed_to_return_response_wrapper")

    def servlet5 = drained[1]
    servlet5.namespace == "rum"
    servlet5.metricName == "injection.failed"
    servlet5.type == "count"
    servlet5.value == 1
    servlet5.common == true
    !servlet5.tags.any { it.startsWith("content_encoding:") }
    servlet5.tags.contains("integration_name:servlet")
    servlet5.tags.contains("integration_version:5")
    servlet5.tags.contains("reason:failed_to_return_response_wrapper")

    def servlet6 = drained[2]
    servlet6.namespace == "rum"
    servlet6.metricName == "injection.failed"
    servlet6.type == "count"
    servlet6.value == 1
    servlet6.common == true
    servlet6.tags.contains("content_encoding:gzip")
    servlet6.tags.contains("integration_name:servlet")
    servlet6.tags.contains("integration_version:6")
    servlet6.tags.contains("reason:failed_to_return_response_wrapper")
  }

  def "test onInjectionSkipped"() {
    when:
    metrics.onInjectionSkipped("3")
    metrics.onInjectionSkipped("5")
    metrics.onInjectionSkipped("6")

    then:
    def drained = metrics.drain()
    drained.size() == 3

    def servlet3 = drained[0]
    servlet3.namespace == "rum"
    servlet3.metricName == "injection.skipped"
    servlet3.type == "count"
    servlet3.value == 1
    servlet3.common == true
    servlet3.tags.contains("integration_name:servlet")
    servlet3.tags.contains("integration_version:3")
    servlet3.tags.contains("reason:should_not_inject")

    def servlet5 = drained[1]
    servlet5.namespace == "rum"
    servlet5.metricName == "injection.skipped"
    servlet5.type == "count"
    servlet5.value == 1
    servlet5.common == true
    servlet5.tags.contains("integration_name:servlet")
    servlet5.tags.contains("integration_version:5")
    servlet5.tags.contains("reason:should_not_inject")

    def servlet6 = drained[2]
    servlet6.namespace == "rum"
    servlet6.metricName == "injection.skipped"
    servlet6.type == "count"
    servlet6.value == 1
    servlet6.common == true
    servlet6.tags.contains("integration_name:servlet")
    servlet6.tags.contains("integration_version:6")
    servlet6.tags.contains("reason:should_not_inject")
  }

  def "test onInitializationSucceed"() {
    when:
    metrics.onInitializationSucceed()

    then:
    def drained = metrics.drain()
    drained.size() == 1

    def metric = drained[0]
    metric.namespace == "rum"
    metric.metricName == "injection.initialization.succeed"
    metric.type == "count"
    metric.value == 1
    metric.common == true
    metric.tags.contains("integration_name:servlet")
    metric.tags.contains("integration_version:N/A")
  }

  def "test onContentSecurityPolicyDetected"() {
    when:
    metrics.onContentSecurityPolicyDetected("5")

    then:
    def drained = metrics.drain()
    drained.size() == 1

    def servlet5 = drained[0]
    servlet5.namespace == "rum"
    servlet5.metricName == "injection.content_security_policy"
    servlet5.type == "count"
    servlet5.value == 1
    servlet5.common == true
    servlet5.tags.contains("integration_name:servlet")
    servlet5.tags.contains("integration_version:5")
    servlet5.tags.contains("kind:header")
    servlet5.tags.contains("reason:csp_header_found")
    servlet5.tags.contains("status:seen")
  }

  def "test onInjectionResponseSize"() {
    when:
    metrics.onInjectionResponseSize("3", 1024)
    metrics.onInjectionResponseSize("5", 2048)

    then:
    def drained = metrics.drainDistributionSeries()
    drained.size() == 2

    def servlet3 = drained[0]
    servlet3.namespace == "rum"
    servlet3.metricName == "injection.response.bytes"
    servlet3.value == 1024
    servlet3.common == true
    servlet3.tags.contains("integration_name:servlet")
    servlet3.tags.contains("integration_version:3")
    servlet3.tags.contains("response_kind:header")

    def servlet5 = drained[1]
    servlet5.namespace == "rum"
    servlet5.metricName == "injection.response.bytes"
    servlet5.value == 2048
    servlet5.common == true
    servlet5.tags.contains("integration_name:servlet")
    servlet5.tags.contains("integration_version:5")
    servlet5.tags.contains("response_kind:header")
  }

  def "test onInjectionTime"() {
    when:
    metrics.onInjectionTime("3", 15L)
    metrics.onInjectionTime("5", 25L)

    then:
    def drained = metrics.drainDistributionSeries()
    drained.size() == 2

    def servlet3 = drained[0]
    servlet3.namespace == "rum"
    servlet3.metricName == "injection.ms"
    servlet3.value == 15
    servlet3.common == true
    servlet3.tags.contains("integration_name:servlet")
    servlet3.tags.contains("integration_version:3")

    def servlet5 = drained[1]
    servlet5.namespace == "rum"
    servlet5.metricName == "injection.ms"
    servlet5.value == 25
    servlet5.common == true
    servlet5.tags.contains("integration_name:servlet")
    servlet5.tags.contains("integration_version:5")
  }

  def "test drain methods"() {
    when:
    metrics.onInjectionSucceed("3")
    metrics.onInjectionTime("3", 10L)

    def drainedData = metrics.drain()
    def data = metrics.drainDistributionSeries()

    def drainedEmpty = metrics.drain()
    def empty = metrics.drainDistributionSeries()

    then:
    drainedData.size() == 1
    data.size() == 1
    drainedEmpty.size() == 0
    empty.size() == 0
  }

  def "test mixed metrics"() {
    when:
    metrics.onInjectionSucceed("3")
    metrics.onInjectionFailed("4", "gzip")
    metrics.onInjectionResponseSize("5", 512)
    metrics.onInjectionTime("6", 20L)
    metrics.onContentSecurityPolicyDetected("3")

    def counts = metrics.drain()
    def distributions = metrics.drainDistributionSeries()

    then:
    counts.size() == 3
    distributions.size() == 2
  }

  def "test close clears queues"() {
    when:
    metrics.onInjectionSucceed("3")
    metrics.onInjectionTime("5", 10L)

    def hasData = !metrics.drain().isEmpty() || !metrics.drainDistributionSeries().isEmpty()

    metrics.close()

    def emptyCounts = metrics.drain()
    def emptyDistributions = metrics.drainDistributionSeries()

    then:
    hasData == true
    emptyCounts.isEmpty()
    emptyDistributions.isEmpty()
  }
}
