package datadog.trace.api.naming

import datadog.trace.api.config.GeneralConfig
import datadog.trace.test.util.DDSpecification
import datadog.trace.api.remoteconfig.ServiceNameCollector

class ExtraServiceProviderNamingV0ForkedTest extends DDSpecification {

  void "Naming schema calls ExtraServicesProvider if provides a service name"() {
    setup:
    final extraServiceProvider = Mock(ServiceNameCollector)
    ServiceNameCollector.INSTANCE = extraServiceProvider
    def ddService = "testService"
    injectSysConfig(GeneralConfig.SERVICE_NAME, ddService)
    final schema = SpanNaming.instance().namingSchema()

    when:
    schema.messaging().inboundService("inboundService", true)

    then:
    1 * extraServiceProvider.addService("inboundService")

    when:
    schema.messaging().inboundService("inboundService", false)

    then:
    0 * extraServiceProvider.addService(_)

    when:
    schema.cache().service("anything")

    then:
    1 * extraServiceProvider.addService("anything")

    when:
    schema.cache().service("hazelcast")

    then:
    1 * extraServiceProvider.addService("hazelcast-sdk")

    when:
    schema.cloud().serviceForRequest("any", null)

    then:
    1 * extraServiceProvider.addService("java-aws-sdk")

    when:
    schema.cloud().serviceForRequest("any", "sns")

    then:
    1 * extraServiceProvider.addService("sns")

    when:
    schema.cloud().serviceForRequest("any", "sqs")

    then:
    1 * extraServiceProvider.addService("sqs")

    when:
    schema.cloud().serviceForRequest("any", "test")

    then:
    1 * extraServiceProvider.addService("java-aws-sdk")

    when:
    schema.database().service("anything")

    then:
    1 * extraServiceProvider.addService("anything")
  }
}
