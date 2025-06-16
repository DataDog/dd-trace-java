package datadog.trace.api

import spock.lang.Specification

import static datadog.trace.api.config.GeneralConfig.RUNTIME_ID_ENABLED
import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_RUNTIME_ID_ENABLED

class ConfigForkedTest extends Specification {

  static final String PREFIX = "dd."

  def getSetting() {
    RUNTIME_ID_ENABLED
  }

  def "test random runtime id generation can be turned off"(){
    setup:
    System.setProperty(PREFIX + getSetting(), "false")

    when:
    def config = new Config()
    then:
    config.runtimeId == ""
  }
}

class RuntimeMetricsRuntimeIdAliasForkedTest extends ConfigForkedTest {
  @Override
  def getSetting() {
    RUNTIME_METRICS_RUNTIME_ID_ENABLED
  }
}
