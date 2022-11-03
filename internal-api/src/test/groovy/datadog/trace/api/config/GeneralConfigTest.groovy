package datadog.trace.api.config

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.ConfigTest.PREFIX
import static datadog.trace.api.config.GeneralConfig.DEFAULT_DOGSTATSD_START_DELAY
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_ARGS
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_NAMED_PIPE
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_PATH
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_START_DELAY
import static datadog.trace.api.config.TracerConfig.TRACE_REPORT_HOSTNAME

class GeneralConfigTest extends DDSpecification {
  def "verify hostname not added to root span tags by default"() {
    setup:
    Properties properties = new Properties()

    when:
    def config = Config.get(properties)

    then:
    !config.localRootSpanTags.containsKey('_dd.hostname')
  }

  def "verify configuration to add hostname to root span tags"() {
    setup:
    Properties properties = new Properties()
    properties.setProperty(TRACE_REPORT_HOSTNAME, 'true')

    when:
    def config = Config.get(properties)

    then:
    config.localRootSpanTags.containsKey('_dd.hostname')
  }

  def "check default config values for DogStatsD"() {

    when:
    def config = Config.get()

    then:
    config.dogStatsDNamedPipe == null
    config.dogStatsDStartDelay == DEFAULT_DOGSTATSD_START_DELAY
    config.dogStatsDPath == null
    config.dogStatsDArgs == []
  }

  def "check overridden config values for DogStatsD"() {
    setup:
    System.setProperty(PREFIX + DOGSTATSD_NAMED_PIPE, "/var/pipe")
    System.setProperty(PREFIX + DOGSTATSD_START_DELAY, "30")
    System.setProperty(PREFIX + DOGSTATSD_PATH, "/usr/lib/dogstatd")
    System.setProperty(PREFIX + DOGSTATSD_ARGS, "start")

    when:
    def config = new Config()

    then:
    config.dogStatsDNamedPipe == "/var/pipe"
    config.dogStatsDStartDelay == 30
    config.dogStatsDPath == "/usr/lib/dogstatd"
    config.dogStatsDArgs == ["start"]
  }
}
