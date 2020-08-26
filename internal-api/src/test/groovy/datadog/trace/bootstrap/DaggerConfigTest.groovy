package datadog.trace.bootstrap

import datadog.trace.bootstrap.config.DaggerConfig
import datadog.trace.bootstrap.config.DaggerConfigComponent
import datadog.trace.bootstrap.config.ProfilingConfig
import datadog.trace.bootstrap.config.TracingConfig
import datadog.trace.util.test.DDSpecification

class DaggerConfigTest extends DDSpecification {

  def "config is not null"() {
    expect:
    DaggerConfig.get() != null
  }

  def "config has defaults"() {
    expect:
    DaggerConfig.get().tracing().enabled()
    !DaggerConfig.get().profiling().enabled()
    DaggerConfig.get().profiling().startDelay() == 10
    DaggerConfig.get().tracing().serviceName() == DaggerConfig.get().profiling().serviceName()
  }

  def "allow mocking"() {
    setup:
    def tracingConfig = Mock(TracingConfig)
    def profilingConfig = Mock(ProfilingConfig)
    def original = DaggerConfig.config
    DaggerConfig.config = DaggerConfigComponent.builder().tracingConfig(tracingConfig).profilingConfig(profilingConfig).build()

    when:
    assert DaggerConfig.get().tracing().enabled() == enabled
    assert DaggerConfig.get().profiling().enabled() == enabled

    then:
    1 * tracingConfig.enabled() >> enabled
    1 * profilingConfig.enabled() >> enabled
    0 * _

    cleanup:
    DaggerConfig.config = original

    where:
    enabled << [true, false]
  }
}
