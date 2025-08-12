package datadog.trace.api.rum

import datadog.trace.api.StatsDClient
import spock.lang.Specification
import spock.lang.Subject

class RumInjectorMetricsTest extends Specification {
  def statsD = Mock(StatsDClient)

  @Subject
  def metrics = new RumInjectorMetrics(statsD)

  // Note: application_id and remote_config_used are dynamic runtime values that depend on
  // the RUM configuration state, so we do not test them here.
  def "test onInjectionSucceed"() {
    when:
    metrics.onInjectionSucceed("3")
    metrics.onInjectionSucceed("5")

    then:
    1 * statsD.count('rum.injection.succeed', 1, _) >> { args ->
      def tags = args[2] as String[]
      assert tags.contains("injector_version:0.1.0")
      assert tags.contains("integration_name:servlet")
      assert tags.contains("integration_version:3")
    }
    1 * statsD.count('rum.injection.succeed', 1, _) >> { args ->
      def tags = args[2] as String[]
      assert tags.contains("injector_version:0.1.0")
      assert tags.contains("integration_name:servlet")
      assert tags.contains("integration_version:5")
    }
    0 * _
  }

  def "test onInjectionFailed"() {
    when:
    metrics.onInjectionFailed("3", "gzip")
    metrics.onInjectionFailed("5", null)

    then:
    1 * statsD.count('rum.injection.failed', 1, _) >> { args ->
      def tags = args[2] as String[]
      assert tags.contains("content_encoding:gzip")
      assert tags.contains("injector_version:0.1.0")
      assert tags.contains("integration_name:servlet")
      assert tags.contains("integration_version:3")
      assert tags.contains("reason:failed_to_return_response_wrapper")
    }
    1 * statsD.count('rum.injection.failed', 1, _) >> { args ->
      def tags = args[2] as String[]
      assert !tags.any { it.startsWith("content_encoding:") }
      assert tags.contains("injector_version:0.1.0")
      assert tags.contains("integration_name:servlet")
      assert tags.contains("integration_version:5")
      assert tags.contains("reason:failed_to_return_response_wrapper")
    }
    0 * _
  }

  def "test onInjectionSkipped"() {
    when:
    metrics.onInjectionSkipped("3")
    metrics.onInjectionSkipped("5")

    then:
    1 * statsD.count('rum.injection.skipped', 1, _) >> { args ->
      def tags = args[2] as String[]
      assert tags.contains("injector_version:0.1.0")
      assert tags.contains("integration_name:servlet")
      assert tags.contains("integration_version:3")
      assert tags.contains("reason:should_not_inject")
    }
    1 * statsD.count('rum.injection.skipped', 1, _) >> { args ->
      def tags = args[2] as String[]
      assert tags.contains("injector_version:0.1.0")
      assert tags.contains("integration_name:servlet")
      assert tags.contains("integration_version:5")
      assert tags.contains("reason:should_not_inject")
    }
    0 * _
  }

  def "test onContentSecurityPolicyDetected"() {
    when:
    metrics.onContentSecurityPolicyDetected("3")
    metrics.onContentSecurityPolicyDetected("5")

    then:
    1 * statsD.count('rum.injection.content_security_policy', 1, _) >> { args ->
      def tags = args[2] as String[]
      assert tags.contains("injector_version:0.1.0")
      assert tags.contains("integration_name:servlet")
      assert tags.contains("integration_version:3")
      assert tags.contains("kind:header")
      assert tags.contains("reason:csp_header_found")
      assert tags.contains("status:seen")
    }
    1 * statsD.count('rum.injection.content_security_policy', 1, _) >> { args ->
      def tags = args[2] as String[]
      assert tags.contains("injector_version:0.1.0")
      assert tags.contains("integration_name:servlet")
      assert tags.contains("integration_version:5")
      assert tags.contains("kind:header")
      assert tags.contains("reason:csp_header_found")
      assert tags.contains("status:seen")
    }
    0 * _
  }

  def "test onInitializationSucceed"() {
    when:
    metrics.onInitializationSucceed()

    then:
    1 * statsD.count('rum.injection.initialization.succeed', 1, _) >> { args ->
      def tags = args[2] as String[]
      assert tags.contains("injector_version:0.1.0")
      assert tags.contains("integration_name:servlet")
      assert tags.contains("integration_version:3,5")
    }
    0 * _
  }

  def "test onInjectionResponseSize with multiple sizes"() {
    when:
    metrics.onInjectionResponseSize("3", 256)
    metrics.onInjectionResponseSize("5", 512)

    then:
    1 * statsD.distribution('rum.injection.response.bytes', 256, _) >> { args ->
      def tags = args[2] as String[]
      assert tags.contains("injector_version:0.1.0")
      assert tags.contains("integration_name:servlet")
      assert tags.contains("integration_version:3")
      assert tags.contains("response_kind:header")
    }
    1 * statsD.distribution('rum.injection.response.bytes', 512, _) >> { args ->
      def tags = args[2] as String[]
      assert tags.contains("injector_version:0.1.0")
      assert tags.contains("integration_name:servlet")
      assert tags.contains("integration_version:5")
      assert tags.contains("response_kind:header")
    }
    0 * _
  }

  def "test onInjectionTime with multiple durations"() {
    when:
    metrics.onInjectionTime("5", 5L)
    metrics.onInjectionTime("3", 10L)

    then:
    1 * statsD.distribution('rum.injection.ms', 5L, _) >> { args ->
      def tags = args[2] as String[]
      assert tags.contains("injector_version:0.1.0")
      assert tags.contains("integration_name:servlet")
      assert tags.contains("integration_version:5")
    }
    1 * statsD.distribution('rum.injection.ms', 10L, _) >> { args ->
      def tags = args[2] as String[]
      assert tags.contains("injector_version:0.1.0")
      assert tags.contains("integration_name:servlet")
      assert tags.contains("integration_version:3")
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
    metrics.onInjectionFailed("5", null)
    metrics.onInjectionSucceed("3")
    metrics.onInjectionSkipped("3")
    metrics.onContentSecurityPolicyDetected("5")
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
