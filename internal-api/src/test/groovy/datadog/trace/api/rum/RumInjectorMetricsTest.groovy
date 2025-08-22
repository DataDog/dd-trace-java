package datadog.trace.api.rum

import datadog.trace.api.StatsDClient
import spock.lang.Specification
import spock.lang.Subject

class RumInjectorMetricsTest extends Specification {
  def statsD = Mock(StatsDClient)

  @Subject
  def metrics = new RumInjectorMetrics(statsD)

  void assertTags(String[] args, String... expectedTags) {
    expectedTags.each { expectedTag ->
      assert args.contains(expectedTag), "Expected tag '$expectedTag' not found in tags: ${args as List}"
    }
  }

  // Note: application_id and remote_config_used tags need dynamic runtime values that depend on
  // the RUM configuration state, so we do not test them here.
  def "test onInjectionSucceed"() {
    when:
    metrics.onInjectionSucceed("3")
    metrics.onInjectionSucceed("5")
    metrics.onInjectionSucceed("6")

    then:
    1 * statsD.count('rum.injection.succeed', 1, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:3")
    }
    1 * statsD.count('rum.injection.succeed', 1, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:5")
    }
    1 * statsD.count('rum.injection.succeed', 1, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:6")
    }
    0 * _
  }

  def "test onInjectionFailed"() {
    when:
    metrics.onInjectionFailed("3", "gzip")
    metrics.onInjectionFailed("5", null)
    metrics.onInjectionFailed("6", "gzip")

    then:
    1 * statsD.count('rum.injection.failed', 1, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "content_encoding:gzip", "integration_name:servlet", "integration_version:3", "reason:failed_to_return_response_wrapper")
    }
    1 * statsD.count('rum.injection.failed', 1, _) >> { args ->
      def tags = args[2] as String[]
      assert !tags.any { it.startsWith("content_encoding:") }
      assertTags(tags, "integration_name:servlet", "integration_version:5", "reason:failed_to_return_response_wrapper")
    }
    1 * statsD.count('rum.injection.failed', 1, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "content_encoding:gzip", "integration_name:servlet", "integration_version:6", "reason:failed_to_return_response_wrapper")
    }
    0 * _
  }

  def "test onInjectionSkipped"() {
    when:
    metrics.onInjectionSkipped("3")
    metrics.onInjectionSkipped("5")
    metrics.onInjectionSkipped("6")

    then:
    1 * statsD.count('rum.injection.skipped', 1, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:3", "reason:should_not_inject")
    }
    1 * statsD.count('rum.injection.skipped', 1, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:5", "reason:should_not_inject")
    }
    1 * statsD.count('rum.injection.skipped', 1, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:6", "reason:should_not_inject")
    }
    0 * _
  }

  def "test onContentSecurityPolicyDetected"() {
    when:
    metrics.onContentSecurityPolicyDetected("3")
    metrics.onContentSecurityPolicyDetected("5")
    metrics.onContentSecurityPolicyDetected("6")

    then:
    1 * statsD.count('rum.injection.content_security_policy', 1, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:3", "kind:header", "reason:csp_header_found", "status:seen")
    }
    1 * statsD.count('rum.injection.content_security_policy', 1, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:5", "kind:header", "reason:csp_header_found", "status:seen")
    }
    1 * statsD.count('rum.injection.content_security_policy', 1, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:6", "kind:header", "reason:csp_header_found", "status:seen")
    }
    0 * _
  }

  def "test onInitializationSucceed"() {
    when:
    metrics.onInitializationSucceed()

    then:
    1 * statsD.count('rum.injection.initialization.succeed', 1, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:N/A")
    }
    0 * _
  }

  def "test onInjectionResponseSize with multiple sizes"() {
    when:
    metrics.onInjectionResponseSize("3", 256)
    metrics.onInjectionResponseSize("5", 512)
    metrics.onInjectionResponseSize("6", 1024)

    then:
    1 * statsD.distribution('rum.injection.response.bytes', 256, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:3", "response_kind:header")
    }
    1 * statsD.distribution('rum.injection.response.bytes', 512, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:5", "response_kind:header")
    }
    1 * statsD.distribution('rum.injection.response.bytes', 1024, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:6", "response_kind:header")
    }
    0 * _
  }

  def "test onInjectionTime with multiple durations"() {
    when:
    metrics.onInjectionTime("5", 5L)
    metrics.onInjectionTime("3", 10L)
    metrics.onInjectionTime("6", 15L)

    then:
    1 * statsD.distribution('rum.injection.ms', 5L, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:5")
    }
    1 * statsD.distribution('rum.injection.ms', 10L, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:3")
    }
    1 * statsD.distribution('rum.injection.ms', 15L, _) >> { args ->
      def tags = args[2] as String[]
      assertTags(tags, "integration_name:servlet", "integration_version:6")
    }
    0 * _
  }

  def "test summary with multiple events in different order"() {
    when:
    metrics.onInitializationSucceed()
    metrics.onContentSecurityPolicyDetected("3")
    metrics.onInjectionSkipped("5")
    metrics.onInjectionFailed("3", "gzip")
    metrics.onInjectionSucceed("3")
    metrics.onInjectionFailed("6", null)
    metrics.onInjectionSucceed("6")
    metrics.onInjectionSkipped("3")
    metrics.onContentSecurityPolicyDetected("6")
    metrics.onInjectionResponseSize("3", 256)
    metrics.onInjectionTime("5", 5L)
    def summary = metrics.summary()

    then:
    summary.contains("initializationSucceed=1")
    summary.contains("injectionSucceed=2")
    summary.contains("injectionFailed=2")
    summary.contains("injectionSkipped=2")
    summary.contains("contentSecurityPolicyDetected=2")
    1 * statsD.count('rum.injection.initialization.succeed', 1, _)
    2 * statsD.count('rum.injection.succeed', 1, _)
    2 * statsD.count('rum.injection.failed', 1, _)
    2 * statsD.count('rum.injection.skipped', 1, _)
    2 * statsD.count('rum.injection.content_security_policy', 1, _)
    1 * statsD.distribution('rum.injection.response.bytes', 256, _)
    1 * statsD.distribution('rum.injection.ms', 5L, _)
    0 * _
  }

  def "test metrics start at zero in summary"() {
    when:
    def summary = metrics.summary()

    then:
    summary.contains("initializationSucceed=0")
    summary.contains("injectionSucceed=0")
    summary.contains("injectionFailed=0")
    summary.contains("injectionSkipped=0")
    summary.contains("contentSecurityPolicyDetected=0")
    0 * _
  }

  def "test close resets counters in summary"() {
    when:
    metrics.onInitializationSucceed()
    metrics.onInjectionSucceed("3")
    metrics.onInjectionFailed("3", "gzip")
    metrics.onInjectionSkipped("3")
    metrics.onContentSecurityPolicyDetected("3")

    def summaryBeforeClose = metrics.summary()
    metrics.close()
    def summaryAfterClose = metrics.summary()

    then:
    summaryBeforeClose.contains("initializationSucceed=1")
    summaryBeforeClose.contains("injectionSucceed=1")
    summaryBeforeClose.contains("injectionFailed=1")
    summaryBeforeClose.contains("injectionSkipped=1")
    summaryBeforeClose.contains("contentSecurityPolicyDetected=1")

    summaryAfterClose.contains("initializationSucceed=0")
    summaryAfterClose.contains("injectionSucceed=0")
    summaryAfterClose.contains("injectionFailed=0")
    summaryAfterClose.contains("injectionSkipped=0")
    summaryAfterClose.contains("contentSecurityPolicyDetected=0")

    1 * statsD.count('rum.injection.initialization.succeed', 1, _)
    1 * statsD.count('rum.injection.succeed', 1, _)
    1 * statsD.count('rum.injection.failed', 1, _)
    1 * statsD.count('rum.injection.skipped', 1, _)
    1 * statsD.count('rum.injection.content_security_policy', 1, _)
    0 * _
  }
}
