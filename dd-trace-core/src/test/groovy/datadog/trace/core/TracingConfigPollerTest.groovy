package datadog.trace.core

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.http.client.HttpClient
import datadog.http.client.HttpUrl
import datadog.metrics.api.Monitoring
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.Product
import datadog.remoteconfig.state.ParsedConfigKey
import datadog.remoteconfig.state.ProductListener
import datadog.trace.api.datastreams.DataStreamsTransactionExtractor
import datadog.trace.core.test.DDCoreSpecification
import java.nio.charset.StandardCharsets
import spock.lang.Timeout

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

  def "test config priority calculation"() {
    setup:
    def configOverrides = new TracingConfigPoller.ConfigOverrides()
    if (service != null || env != null) {
      configOverrides.serviceTarget = new TracingConfigPoller.ServiceTarget(
        service: service,
        env: env,
        )
    }
    if (clusterName != null) {
      configOverrides.k8sTargetV2 = new TracingConfigPoller.K8sTargetV2(
        clusterTargets: [
          new TracingConfigPoller.ClusterTarget(
          clusterName: clusterName,
          enabled: true,
          )
        ]
        )
    }
    configOverrides.libConfig = new TracingConfigPoller.LibConfig()

    when:
    def priority = configOverrides.getOverridePriority()

    then:
    priority == expectedPriority

    where:
    service        | env       | clusterName    | expectedPriority
    "test-service" | "staging" | null           | 5
    "test-service" | "*"       | null           | 4
    "*"            | "staging" | null           | 3
    null           | null      | "test-cluster" | 2
    "*"            | "*"       | null           | 1
  }


  def "test actual config commit with service and org level configs"() {
    setup:
    def orgKey = ParsedConfigKey.parse("datadog/2/APM_TRACING/org_config/config")
    def serviceKey = ParsedConfigKey.parse("datadog/2/APM_TRACING/service_config/config")
    def poller = Mock(ConfigurationPoller)
    def sco = new SharedCommunicationObjects(
      agentHttpClient: Mock(HttpClient),
      monitoring: Mock(Monitoring),
      agentUrl: HttpUrl.parse('https://example.com'),
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
        "service_target": {
          "service": "*",
          "env": "*"
        },
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
          "tracing_sampling_rate": 1.3,
          "data_streams_transaction_extractors": [
            {
              "name": "test",
              "type": "unknown",
              "value": "value"
            }
          ]
        }
      }
    """.getBytes(StandardCharsets.UTF_8), null)

    // Commit both configs
    updater.commit()

    then:
    // Service level config should take precedence due to higher priority (4 vs 1)
    tracer.captureTraceConfig().serviceMapping == ["service-specific": "service-mapped"]
    tracer.captureTraceConfig().traceSampleRate == 1.0 // should be clamped to 1.0
    tracer.captureTraceConfig().requestHeaderTags == ["x-custom-header": "custom.header"]
    tracer.captureTraceConfig().responseHeaderTags == ["x-custom-header": "custom.header"]
    tracer.captureTraceConfig().getDataStreamsTransactionExtractors().size() == 1
    tracer.captureTraceConfig().getDataStreamsTransactionExtractors()[0].name == "test"
    tracer.captureTraceConfig().getDataStreamsTransactionExtractors()[0].type == DataStreamsTransactionExtractor.Type.UNKNOWN
    tracer.captureTraceConfig().getDataStreamsTransactionExtractors()[0].value == "value"

    when:
    // Remove service level config
    updater.remove(serviceKey, null)
    updater.commit()

    then:
    // Should fall back to org level config
    tracer.captureTraceConfig().serviceMapping == ["org-service": "org-mapped"]
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

  def "test two org levels config setting different flags works"() {
    setup:
    def orgConfig1Key = ParsedConfigKey.parse("datadog/2/APM_TRACING/org_config/config1")
    def orgConfig2Key = ParsedConfigKey.parse("datadog/2/APM_TRACING/org_config/config2")
    def poller = Mock(ConfigurationPoller)
    def sco = new SharedCommunicationObjects(
      agentHttpClient: Mock(HttpClient),
      monitoring: Mock(Monitoring),
      agentUrl: HttpUrl.parse('https://example.com'),
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
    tracer.captureTraceConfig().isTraceEnabled() == true
    tracer.captureTraceConfig().isDataStreamsEnabled() == false

    when:
    // Add org level config with ApmTracing enabled
    updater.accept(orgConfig1Key, """
      {
        "service_target": {
          "service": "*",
          "env": "*"
        },
        "lib_config": {
           "tracing_enabled": true
        }
      }
    """.getBytes(StandardCharsets.UTF_8), null)

    // Add second org level config with DataStreams enabled
    updater.accept(orgConfig2Key, """
      {
        "service_target": {
          "service": "*",
          "env": "*"
        },
        "lib_config": {
          "data_streams_enabled": true
        }
      }
    """.getBytes(StandardCharsets.UTF_8), null)

    // Commit both configs
    updater.commit()

    then:
    // Both org level configs should be merged, with data streams enabled
    tracer.captureTraceConfig().isTraceEnabled() == true
    tracer.captureTraceConfig().isDataStreamsEnabled() == true

    cleanup:
    tracer?.close()
  }
}
