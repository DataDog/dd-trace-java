package datadog.trace.api.naming

import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.config.TracerConfig
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.ExtraServicesProvider

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

  void "Naming schema calls ExtraServicesProvider if provides a service name"() {
    setup:
    final schema = SpanNaming.instance().namingSchema()
    ExtraServicesProvider.get().clear()

    when:
    schema.messaging().inboundService("inboundService", true)

    then:
    checkExtraServices("inboundService") == true

    when:
    schema.messaging().inboundService("inboundService", false)

    then:
    checkExtraServices("inboundService") == false

    when:
    schema.cache().service("cacheService")

    then:
    checkExtraServices("cacheService") == true

    when:
    schema.cache().service("hazelcast")

    then:
    checkExtraServices("hazelcast-sdk") == true

    when:
    schema.cloud().serviceForRequest("any", null)

    then:
    checkExtraServices("java-aws-sdk") == true

    when:
    schema.cloud().serviceForRequest("any", "sns")

    then:
    checkExtraServices("sns") == true

    when:
    schema.cloud().serviceForRequest("any", "sqs")

    then:
    checkExtraServices("sqs") == true

    when:
    schema.cloud().serviceForRequest("any", "test")

    then:
    checkExtraServices("java-aws-sdk") == true

    when:
    schema.database().service("databaseService")

    then:
    checkExtraServices("databaseService") == true
  }

  private boolean checkExtraServices(String serviceName) {
    final extraServices = ExtraServicesProvider.get().getExtraServices()
    if(extraServices == null){
      return false
    }
    final result = ExtraServicesProvider.get().getExtraServices().contains(serviceName)
    ExtraServicesProvider.get().clear()
    return result
  }
}
