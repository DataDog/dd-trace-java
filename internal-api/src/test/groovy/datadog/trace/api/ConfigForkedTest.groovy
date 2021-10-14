package datadog.trace.api

import spock.lang.Specification

import static datadog.trace.api.config.GeneralConfig.RUNTIME_ID_ENABLED

class ConfigForkedTest extends Specification {

  static final String PREFIX = "dd."

  def "test random runtime id generation can be turned off"(){
    setup:
    System.setProperty(PREFIX + RUNTIME_ID_ENABLED, "false")

    when:
    def config = new Config()
    then:
    config.runtimeId == ""
  }
}
