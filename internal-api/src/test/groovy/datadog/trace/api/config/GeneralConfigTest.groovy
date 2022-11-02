package datadog.trace.api.config

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

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
}
