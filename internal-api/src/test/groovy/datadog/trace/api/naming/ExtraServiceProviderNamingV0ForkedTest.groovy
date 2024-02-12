package datadog.trace.api.naming

import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.config.TracerConfig
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.ExtraServicesProvider

class ExtraServiceProviderNamingV0ForkedTest extends DDSpecification {

  void "Naming schema calls ExtraServicesProvider if provides a service name"() {
    setup:
    final extraServiceProvider = Mock(ExtraServicesProvider)
    ExtraServicesProvider.INSTANCE = extraServiceProvider
    def ddService = "testService"
    injectSysConfig(TracerConfig.TRACE_REMOVE_INTEGRATION_SERVICE_NAMES_ENABLED, "false")
    injectSysConfig(GeneralConfig.SERVICE_NAME, ddService)
    final schema = SpanNaming.instance().namingSchema()

    when:
    schema.messaging().inboundService("inboundService", true)

    then:
    1 * extraServiceProvider.maybeAddExtraService("inboundService")

    when:
    schema.messaging().inboundService("inboundService", false)

    then:
    0 * extraServiceProvider.maybeAddExtraService(_)

    when:
    schema.cache().service("anything")

    then:
    1 * extraServiceProvider.maybeAddExtraService("anything")

    when:
    schema.cache().service("hazelcast")

    then:
    1 * extraServiceProvider.maybeAddExtraService("hazelcast-sdk")

    when:
    schema.cloud().serviceForRequest("any", null)

    then:
    1 * extraServiceProvider.maybeAddExtraService("java-aws-sdk")

    when:
    schema.cloud().serviceForRequest("any", "sns")

    then:
    1 * extraServiceProvider.maybeAddExtraService("sns")

    when:
    schema.cloud().serviceForRequest("any", "sqs")

    then:
    1 * extraServiceProvider.maybeAddExtraService("sqs")

    when:
    schema.cloud().serviceForRequest("any", "test")

    then:
    1 * extraServiceProvider.maybeAddExtraService("java-aws-sdk")

    when:
    schema.database().service("anything")

    then:
    1 * extraServiceProvider.maybeAddExtraService("anything")
  }
}
