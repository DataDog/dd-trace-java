package datadog.trace.common.writer

import static datadog.trace.api.config.TracerConfig.PRIORITIZATION_TYPE

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.trace.api.Config
import datadog.trace.api.intake.TrackType
import datadog.trace.common.sampling.Sampler
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.Prioritization
import datadog.trace.common.writer.ddintake.DDEvpProxyApi
import datadog.trace.common.writer.ddintake.DDIntakeApi
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.test.util.DDSpecification
import groovy.json.JsonBuilder
import java.util.stream.Collectors
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

class WriterFactoryTest extends DDSpecification {

  def "test writer creation for #configuredType when agentHasEvpProxy=#hasEvpProxy evpProxySupportsCompression=#evpProxySupportsCompression ciVisibilityAgentless=#isCiVisibilityAgentlessEnabled"() {
    setup:
    def config = Mock(Config)
    config.apiKey >> "my-api-key"
    config.agentUrl >> "http://my-agent.url"
    config.getEnumValue(PRIORITIZATION_TYPE, _, _) >> Prioritization.FAST_LANE
    config.tracerMetricsEnabled >> true
    config.isCiVisibilityEnabled() >> true
    config.isCiVisibilityCodeCoverageEnabled() >> false

    // Mock agent info response
    def response = buildHttpResponse(hasEvpProxy, evpProxySupportsCompression, HttpUrl.parse(config.agentUrl + "/info"))

    // Mock HTTP client that simulates delayed response for async feature discovery
    def mockCall = Mock(Call)
    def mockHttpClient = Mock(OkHttpClient)
    mockCall.execute() >> {
      // Add a delay
      sleep(400)
      return response
    }
    mockHttpClient.newCall(_ as Request) >> mockCall

    // Create SharedCommunicationObjects with mocked HTTP client
    def sharedComm = new SharedCommunicationObjects()
    sharedComm.agentHttpClient = mockHttpClient
    sharedComm.agentUrl = HttpUrl.parse(config.agentUrl)
    sharedComm.createRemaining(config)

    def sampler = Mock(Sampler)

    when:
    config.ciVisibilityAgentlessEnabled >> isCiVisibilityAgentlessEnabled

    def writer = WriterFactory.createWriter(config, sharedComm, sampler, null, HealthMetrics.NO_OP, configuredType)

    def apis
    def apiClasses
    if (expectedApiClasses != null) {
      apis = ((RemoteWriter) writer).apis
      apiClasses = apis.stream().map(Object::getClass).collect(Collectors.toList())
    } else {
      apis = Collections.emptyList()
      apiClasses = Collections.emptyList()
    }

    then:
    writer.class == expectedWriterClass
    expectedApiClasses == null || apiClasses == expectedApiClasses
    expectedApiClasses == null || apis.stream().allMatch(api -> api.isCompressionEnabled() == isCompressionEnabled)

    where:
    configuredType                             | hasEvpProxy | evpProxySupportsCompression | isCiVisibilityAgentlessEnabled | expectedWriterClass  | expectedApiClasses | isCompressionEnabled
    "LoggingWriter"                            | true        | false                       | true                           | LoggingWriter        | null               | false
    "PrintingWriter"                           | true        | false                       | true                           | PrintingWriter       | null               | false
    "TraceStructureWriter"                     | true        | false                       | true                           | TraceStructureWriter | null               | false
    "MultiWriter:LoggingWriter,PrintingWriter" | true        | false                       | true                           | MultiWriter          | null               | false
    "DDIntakeWriter"                           | true        | false                       | true                           | DDIntakeWriter       | [DDIntakeApi]      | true
    "DDIntakeWriter"                           | true        | false                       | false                          | DDIntakeWriter       | [DDEvpProxyApi]    | false
    "DDIntakeWriter"                           | false       | false                       | true                           | DDIntakeWriter       | [DDIntakeApi]      | true
    "DDIntakeWriter"                           | false       | false                       | false                          | DDIntakeWriter       | [DDIntakeApi]      | true
    "DDAgentWriter"                            | true        | false                       | true                           | DDIntakeWriter       | [DDIntakeApi]      | true
    "DDAgentWriter"                            | true        | false                       | false                          | DDIntakeWriter       | [DDEvpProxyApi]    | false
    "DDAgentWriter"                            | true        | true                        | false                          | DDIntakeWriter       | [DDEvpProxyApi]    | true
    "DDAgentWriter"                            | false       | false                       | true                           | DDIntakeWriter       | [DDIntakeApi]      | true
    "DDAgentWriter"                            | false       | false                       | false                          | DDAgentWriter        | [DDAgentApi]       | false
    "not-found"                                | true        | false                       | true                           | DDIntakeWriter       | [DDIntakeApi]      | true
    "not-found"                                | true        | false                       | false                          | DDIntakeWriter       | [DDEvpProxyApi]    | false
    "not-found"                                | false       | false                       | true                           | DDIntakeWriter       | [DDIntakeApi]      | true
    "not-found"                                | false       | false                       | false                          | DDAgentWriter        | [DDAgentApi]       | false
  }

  def "test writer creation for #configuredType when agentHasEvpProxy=#hasEvpProxy llmObsAgentless=#isLlmObsAgentlessEnabled for LLM Observability"() {
    setup:
    def config = Mock(Config)
    config.apiKey >> "my-api-key"
    config.agentUrl >> "http://my-agent.url"
    config.getEnumValue(PRIORITIZATION_TYPE, _, _) >> Prioritization.FAST_LANE
    config.tracerMetricsEnabled >> true
    config.isLlmObsEnabled() >> true

    // Mock agent info response
    def response
    if (agentRunning) {
      response = buildHttpResponse(hasEvpProxy, true, HttpUrl.parse(config.agentUrl + "/info"))
    } else {
      response = buildHttpResponseNotOk(HttpUrl.parse(config.agentUrl + "/info"))
    }

    // Mock HTTP client that simulates delayed response for async feature discovery
    def mockCall = Mock(Call)
    def mockHttpClient = Mock(OkHttpClient)
    mockCall.execute() >> {
      // Add a delay
      sleep(400)
      return response
    }
    mockHttpClient.newCall(_ as Request) >> mockCall

    // Create SharedCommunicationObjects with mocked HTTP client
    def sharedComm = new SharedCommunicationObjects()
    sharedComm.agentHttpClient = mockHttpClient
    sharedComm.agentUrl = HttpUrl.parse(config.agentUrl)
    sharedComm.createRemaining(config)

    def sampler = Mock(Sampler)

    when:
    config.llmObsAgentlessEnabled >> isLlmObsAgentlessEnabled

    def writer = WriterFactory.createWriter(config, sharedComm, sampler, null, HealthMetrics.NO_OP, configuredType)
    def llmObsApiClasses = ((RemoteWriter) writer).apis
    .stream()
    .filter(api -> {
      try {
        def trackTypeField = api.class.getDeclaredField("trackType")
        trackTypeField.setAccessible(true)
        return trackTypeField.get(api) == TrackType.LLMOBS
      } catch (Exception e) {
        return false
      }
    })
    .map(Object::getClass)
    .collect(Collectors.toList())

    then:
    writer.class == expectedWriterClass
    llmObsApiClasses == expectedLlmObsApiClasses

    where:
    configuredType                             | agentRunning | hasEvpProxy | isLlmObsAgentlessEnabled |expectedWriterClass  | expectedLlmObsApiClasses
    "DDIntakeWriter"                           | true         | true        | false                    | DDIntakeWriter      | [DDEvpProxyApi]
    "DDIntakeWriter"                           | true         | false       | false                    | DDIntakeWriter      | [DDIntakeApi]
    "DDIntakeWriter"                           | false        | false       | false                    | DDIntakeWriter      | [DDIntakeApi]
    "DDIntakeWriter"                           | true         | true        | true                     | DDIntakeWriter      | [DDIntakeApi]
    "DDIntakeWriter"                           | true         | false       | true                     | DDIntakeWriter      | [DDIntakeApi]
    "DDIntakeWriter"                           | false        | false       | true                     | DDIntakeWriter      | [DDIntakeApi]
  }

  Response buildHttpResponse(boolean hasEvpProxy, boolean evpProxySupportsCompression, HttpUrl agentUrl) {
    def endpoints = []
    if (hasEvpProxy && evpProxySupportsCompression) {
      endpoints = [DDAgentFeaturesDiscovery.V4_EVP_PROXY_ENDPOINT]
    } else if (hasEvpProxy) {
      endpoints = [DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT]
    } else {
      endpoints = [DDAgentFeaturesDiscovery.V4_ENDPOINT]
    }

    def response = [
      "version" : "7.40.0",
      "endpoints"  : endpoints,
    ]

    def builder = new Response.Builder()
    .code(200)
    .message("OK")
    .protocol(Protocol.HTTP_1_1)
    .request(new Request.Builder().url(agentUrl.resolve("/info")).build())
    .body(ResponseBody.create(MediaType.parse("application/json"), new JsonBuilder(response).toString()))
    return builder.build()
  }

  Response buildHttpResponseNotOk(HttpUrl agentUrl) {
    def builder = new Response.Builder()
    .code(500)
    .message("ERROR")
    .protocol(Protocol.HTTP_1_1)
    .request(new Request.Builder().url(agentUrl.resolve("/info")).build())
    return builder.build()
  }
}
