package datadog.trace.core

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.monitor.Monitoring
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.Product
import datadog.remoteconfig.state.ParsedConfigKey
import datadog.remoteconfig.state.ProductListener
import datadog.trace.api.Config
import datadog.trace.api.DynamicConfig
import datadog.trace.core.test.DDCoreSpecification
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import spock.lang.Timeout

import java.nio.charset.StandardCharsets

@Timeout(10)
class TracingConfigPollerTest extends DDCoreSpecification {

  def "test mergeLibConfigs with null and non-null values"() {
    setup:
    def config1 = new TracingConfigPoller.LibConfig() // all nulls
    def config2 = new TracingConfigPoller.LibConfig(
      tracingEnabled: true,
      debugEnabled: false,
      runtimeMetricsEnabled: true,
      logsInjectionEnabled: false,
      dataStreamsEnabled: true,
      traceSampleRate: 0.5,
      dynamicInstrumentationEnabled: true,
      exceptionReplayEnabled: false,
      codeOriginEnabled: true,
      liveDebuggingEnabled: false
      )
    def config3 = new TracingConfigPoller.LibConfig(
      tracingEnabled: false,
      debugEnabled: true,
      runtimeMetricsEnabled: false,
      logsInjectionEnabled: true,
      dataStreamsEnabled: false,
      traceSampleRate: 0.8,
      dynamicInstrumentationEnabled: false,
      exceptionReplayEnabled: true,
      codeOriginEnabled: false,
      liveDebuggingEnabled: true
      )

    when:
    def merged = TracingConfigPoller.LibConfig.mergeLibConfigs([config1, config2, config3])

    then:
    merged != null
    // Should take first non-null values from config2
    merged.tracingEnabled == true
    merged.debugEnabled == false
    merged.runtimeMetricsEnabled == true
    merged.logsInjectionEnabled == false
    merged.dataStreamsEnabled == true
    merged.traceSampleRate == 0.5
    merged.dynamicInstrumentationEnabled == true
    merged.exceptionReplayEnabled == false
    merged.codeOriginEnabled == true
    merged.liveDebuggingEnabled == false
  }

  def "test single service+env config priority calculation"() {
    setup:
    def configOverrides = new TracingConfigPoller.ConfigOverrides()
    configOverrides.serviceTarget = new TracingConfigPoller.ServiceTarget(
      service: "test-service",
      env: "staging"
      )
    configOverrides.libConfig = new TracingConfigPoller.LibConfig(
      tracingEnabled: false,
      debugEnabled: true,
      runtimeMetricsEnabled: false,
      logsInjectionEnabled: true,
      dataStreamsEnabled: false,
      traceSampleRate: 0.3,
      dynamicInstrumentationEnabled: true,
      exceptionReplayEnabled: false,
      codeOriginEnabled: true,
      liveDebuggingEnabled: false
      )

    when:
    def priority = configOverrides.getOverridePriority()

    then:
    priority == 5 // highest priority for service + environment
    configOverrides.isSingleService() == true
    configOverrides.isSingleEnvironment() == true
    configOverrides.isClusterTarget() == false
  }

  def "test org level + service level config priority comparison"() {
    setup:
    def orgConfig = new TracingConfigPoller.ConfigOverrides()
    orgConfig.libConfig = new TracingConfigPoller.LibConfig(
      tracingEnabled: true,
      debugEnabled: false,
      runtimeMetricsEnabled: true,
      logsInjectionEnabled: false,
      dataStreamsEnabled: true,
      traceSampleRate: 0.7,
      dynamicInstrumentationEnabled: false,
      exceptionReplayEnabled: true,
      codeOriginEnabled: false,
      liveDebuggingEnabled: true
      )

    def serviceConfig = new TracingConfigPoller.ConfigOverrides()
    serviceConfig.serviceTarget = new TracingConfigPoller.ServiceTarget(
      service: "test-service",
      env: "*"
      )
    serviceConfig.libConfig = new TracingConfigPoller.LibConfig(
      tracingEnabled: false,
      debugEnabled: true,
      runtimeMetricsEnabled: false,
      logsInjectionEnabled: true,
      dataStreamsEnabled: false,
      traceSampleRate: 0.2,
      dynamicInstrumentationEnabled: true,
      exceptionReplayEnabled: false,
      codeOriginEnabled: true,
      liveDebuggingEnabled: false
      )

    when:
    def orgPriority = orgConfig.getOverridePriority()
    def servicePriority = serviceConfig.getOverridePriority()

    then:
    orgPriority == 1 // lowest priority for org level
    servicePriority == 4 // higher priority for service level
    servicePriority > orgPriority

    and:
    orgConfig.isSingleService() == false
    orgConfig.isSingleEnvironment() == false
    orgConfig.isClusterTarget() == false

    serviceConfig.isSingleService() == true
    serviceConfig.isSingleEnvironment() == false // env is "*"
    serviceConfig.isClusterTarget() == false
  }

  def "test actual config commit with service and org level configs"() {
    setup:
    def orgKey = ParsedConfigKey.parse("datadog/2/APM_TRACING/org_config/config")
    def serviceKey = ParsedConfigKey.parse("datadog/2/APM_TRACING/service_config/config")
    def poller = Mock(ConfigurationPoller)
    def sco = new SharedCommunicationObjects(
      okHttpClient: Mock(OkHttpClient),
      monitoring: Mock(Monitoring),
      agentUrl: HttpUrl.get('https://example.com'),
      featuresDiscovery: Mock(DDAgentFeaturesDiscovery),
      configurationPoller: poller
      )

    def updater

    when:
    def tracer = CoreTracer.builder()
      .sharedCommunicationObjects(sco)
      .pollForTracingConfiguration()
      .build()

    then:
    1 * poller.addListener(Product.APM_TRACING, _ as ProductListener) >> {
      updater = it[1] // capture config updater for further testing
    }
    and:
    tracer.captureTraceConfig().serviceMapping == [:]
    tracer.captureTraceConfig().traceSampleRate == null

    when:
    // Add org level config (priority 1) - should set service mapping
    updater.accept(orgKey, """
      {
        "lib_config": {
          "tracing_service_mapping": [
            {
              "from_key": "org-service",
              "to_name": "org-mapped"
            }
          ],
          "tracing_sampling_rate": 0.7
        }
      }
    """.getBytes(StandardCharsets.UTF_8), null)

    // Add service level config (priority 4) - should override service mapping and add header tags
    updater.accept(serviceKey, """
      {
        "service_target": {
          "service": "test-service",
          "env": "*"
        },
        "lib_config": {
          "tracing_service_mapping": [
            {
              "from_key": "service-specific",
              "to_name": "service-mapped"
            }
          ],
          "tracing_header_tags": [
            {
              "header": "X-Custom-Header",
              "tag_name": "custom.header"
            }
          ],
          "tracing_sampling_rate": 0.3
        }
      }
    """.getBytes(StandardCharsets.UTF_8), null)

    // Commit both configs
    updater.commit()

    then:
    // Service level config should take precedence due to higher priority (4 vs 1)
    tracer.captureTraceConfig().serviceMapping == ["service-specific":"service-mapped"]
    tracer.captureTraceConfig().traceSampleRate == 0.3
    tracer.captureTraceConfig().requestHeaderTags == ["x-custom-header":"custom.header"]
    tracer.captureTraceConfig().responseHeaderTags == ["x-custom-header":"custom.header"]

    when:
    // Remove service level config
    updater.remove(serviceKey, null)
    updater.commit()

    then:
    // Should fall back to org level config
    tracer.captureTraceConfig().serviceMapping == ["org-service":"org-mapped"]
    tracer.captureTraceConfig().traceSampleRate == 0.7
    tracer.captureTraceConfig().requestHeaderTags == [:]
    tracer.captureTraceConfig().responseHeaderTags == [:]

    when:
    // Remove org level config
    updater.remove(orgKey, null)
    updater.commit()

    then:
    // Should have no configs
    tracer.captureTraceConfig().serviceMapping == [:]
    tracer.captureTraceConfig().traceSampleRate == null

    cleanup:
    tracer?.close()
  }
}
