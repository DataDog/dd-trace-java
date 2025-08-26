package datadog.telemetry

import datadog.telemetry.api.Integration
import datadog.telemetry.dependency.Dependency
import ConfigOrigin
import ConfigSetting
import spock.lang.Specification

class ExtendedHeartbeatDataSpecification extends Specification {

  def dependency = new Dependency("name", "version", "source", "hash")
  def configSetting = ConfigSetting.of("key", "value", ConfigOrigin.DEFAULT)
  def integration = new Integration("integration", true)

  def 'discard dependencies after exceeding limit'() {
    setup:
    def extHeartbeatData = new ExtendedHeartbeatData(limit)

    when:
    (limit + 1).times {
      extHeartbeatData.pushDependency(dependency)
    }

    then:
    def snapshot = extHeartbeatData.snapshot()
    int i = 0
    while (snapshot.hasDependencyEvent()) {
      snapshot.nextDependencyEvent()
      i++
    }
    i == limit

    where:
    limit << [0, 2, 10]
  }

  def 'return all collected data'() {
    setup:
    def extHeartbeatData = new ExtendedHeartbeatData()

    when:
    def s0 = extHeartbeatData.snapshot()

    then:
    s0.isEmpty()

    when:
    extHeartbeatData.pushDependency(dependency)
    extHeartbeatData.pushConfigSetting(configSetting)
    extHeartbeatData.pushIntegration(integration)

    then:
    def s1 = extHeartbeatData.snapshot()

    !s1.isEmpty()

    s1.hasDependencyEvent()
    s1.nextDependencyEvent() == dependency
    !s1.hasDependencyEvent()

    !s1.isEmpty()

    s1.hasConfigChangeEvent()
    s1.nextConfigChangeEvent() == configSetting
    !s1.hasConfigChangeEvent()

    !s1.isEmpty()

    s1.hasIntegrationEvent()
    s1.nextIntegrationEvent() == integration
    !s1.hasIntegrationEvent()

    s1.isEmpty()

    when: 'another snapshot includes all data'
    def s2 = extHeartbeatData.snapshot()

    then:
    !s2.isEmpty()
    s2.hasDependencyEvent()
    s2.hasConfigChangeEvent()
    s2.hasIntegrationEvent()
  }
}
