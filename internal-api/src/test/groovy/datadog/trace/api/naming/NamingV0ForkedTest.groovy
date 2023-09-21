package datadog.trace.api.naming

import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.config.TracerConfig
import datadog.trace.test.util.DDSpecification

class NamingV0ForkedTest extends DDSpecification {
  def "v0 should not set service when DD_TRACE_REMOVE_INTEGRATION_SERVICE_NAMES_ENABLED is true"() {
    setup:
    def ddService = "testService"
    injectSysConfig(TracerConfig.TRACE_REMOVE_INTEGRATION_SERVICE_NAMES_ENABLED, "true")
    injectSysConfig(GeneralConfig.SERVICE_NAME, ddService)

    when:
    def schema = SpanNaming.instance().namingSchema()

    then:
    assert SpanNaming.instance().version() == 0
    assert !schema.allowInferredServices()
    assert schema.messaging().inboundService("anything", true) == null
    assert schema.messaging().outboundService("anything", true) == null
    assert schema.database().service("anything") == null
    assert schema.cache().service("anything") == null
    assert schema.cloud().serviceForRequest("any", "anything") == null
  }
}
