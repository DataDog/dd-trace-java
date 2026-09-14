package datadog.trace.common.writer;

import static datadog.trace.api.config.TracerConfig.PRIORITIZATION_TYPE;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.config.OtlpConfig;
import datadog.trace.api.intake.TrackType;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.test.util.DDJavaSpecification;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.tabletest.junit.TableTest;

class WriterFactoryTest extends DDJavaSpecification {

  @TableTest({
    "scenario                                    | configuredType                             | hasEvpProxy | evpProxySupportsCompression | isCiVisibilityAgentlessEnabled | expectedWriterClass                              | expectedApiClass                                   | isCompressionEnabled",
    "LoggingWriter agentless                     | LoggingWriter                              | true        | false                       | true                           | datadog.trace.common.writer.LoggingWriter        |                                                    | false               ",
    "PrintingWriter agentless                    | PrintingWriter                             | true        | false                       | true                           | datadog.trace.common.writer.PrintingWriter       |                                                    | false               ",
    "TraceStructureWriter agentless              | TraceStructureWriter                       | true        | false                       | true                           | datadog.trace.common.writer.TraceStructureWriter |                                                    | false               ",
    "MultiWriter agentless                       | 'MultiWriter:LoggingWriter,PrintingWriter' | true        | false                       | true                           | datadog.trace.common.writer.MultiWriter          |                                                    | false               ",
    "DDIntakeWriter evp agentless                | DDIntakeWriter                             | true        | false                       | true                           | datadog.trace.common.writer.DDIntakeWriter       | datadog.trace.common.writer.ddintake.DDIntakeApi   | true                ",
    "DDIntakeWriter evp not agentless            | DDIntakeWriter                             | true        | false                       | false                          | datadog.trace.common.writer.DDIntakeWriter       | datadog.trace.common.writer.ddintake.DDEvpProxyApi | false               ",
    "DDIntakeWriter no evp agentless             | DDIntakeWriter                             | false       | false                       | true                           | datadog.trace.common.writer.DDIntakeWriter       | datadog.trace.common.writer.ddintake.DDIntakeApi   | true                ",
    "DDIntakeWriter no evp not agentless         | DDIntakeWriter                             | false       | false                       | false                          | datadog.trace.common.writer.DDIntakeWriter       | datadog.trace.common.writer.ddintake.DDIntakeApi   | true                ",
    "DDAgentWriter evp agentless                 | DDAgentWriter                              | true        | false                       | true                           | datadog.trace.common.writer.DDIntakeWriter       | datadog.trace.common.writer.ddintake.DDIntakeApi   | true                ",
    "DDAgentWriter evp not agentless             | DDAgentWriter                              | true        | false                       | false                          | datadog.trace.common.writer.DDIntakeWriter       | datadog.trace.common.writer.ddintake.DDEvpProxyApi | false               ",
    "DDAgentWriter evp compression not agentless | DDAgentWriter                              | true        | true                        | false                          | datadog.trace.common.writer.DDIntakeWriter       | datadog.trace.common.writer.ddintake.DDEvpProxyApi | true                ",
    "DDAgentWriter no evp agentless              | DDAgentWriter                              | false       | false                       | true                           | datadog.trace.common.writer.DDIntakeWriter       | datadog.trace.common.writer.ddintake.DDIntakeApi   | true                ",
    "DDAgentWriter no evp not agentless          | DDAgentWriter                              | false       | false                       | false                          | datadog.trace.common.writer.DDAgentWriter        | datadog.trace.common.writer.ddagent.DDAgentApi     | false               ",
    "not-found evp agentless                     | 'not-found'                                | true        | false                       | true                           | datadog.trace.common.writer.DDIntakeWriter       | datadog.trace.common.writer.ddintake.DDIntakeApi   | true                ",
    "not-found evp not agentless                 | 'not-found'                                | true        | false                       | false                          | datadog.trace.common.writer.DDIntakeWriter       | datadog.trace.common.writer.ddintake.DDEvpProxyApi | false               ",
    "not-found no evp agentless                  | 'not-found'                                | false       | false                       | true                           | datadog.trace.common.writer.DDIntakeWriter       | datadog.trace.common.writer.ddintake.DDIntakeApi   | true                ",
    "not-found no evp not agentless              | 'not-found'                                | false       | false                       | false                          | datadog.trace.common.writer.DDAgentWriter        | datadog.trace.common.writer.ddagent.DDAgentApi     | false               "
  })
  void testWriterCreationForCiVisibility(
      String configuredType,
      boolean hasEvpProxy,
      boolean evpProxySupportsCompression,
      boolean isCiVisibilityAgentlessEnabled,
      Class<?> expectedWriterClass,
      Class<?> expectedApiClass,
      boolean isCompressionEnabled)
      throws Exception {
    Config config = mock(Config.class);
    when(config.getApiKey()).thenReturn("my-api-key");
    when(config.getAgentUrl()).thenReturn("http://my-agent.url");
    //noinspection unchecked
    doReturn(Prioritization.FAST_LANE)
        .when(config)
        .getEnumValue(
            eq(PRIORITIZATION_TYPE),
            (Class<Prioritization>) any(Class.class),
            any(Prioritization.class));
    when(config.isTracerMetricsEnabled()).thenReturn(true);
    when(config.isCiVisibilityEnabled()).thenReturn(true);
    when(config.isCiVisibilityCodeCoverageEnabled()).thenReturn(false);
    when(config.isCiVisibilityAgentlessEnabled()).thenReturn(isCiVisibilityAgentlessEnabled);

    // Mock agent info response
    Response response =
        buildHttpResponse(
            hasEvpProxy, evpProxySupportsCompression, HttpUrl.parse("http://my-agent.url/info"));

    // Mock HTTP client that simulates delayed response for async feature discovery
    Call mockCall = mock(Call.class);
    OkHttpClient mockHttpClient = mock(OkHttpClient.class);
    when(mockCall.execute())
        .thenAnswer(
            inv -> {
              // Add a delay
              Thread.sleep(400);
              return response;
            });
    when(mockHttpClient.newCall(any(Request.class))).thenReturn(mockCall);

    // Create SharedCommunicationObjects with mocked HTTP client
    SharedCommunicationObjects sharedComm = new SharedCommunicationObjects();
    sharedComm.agentHttpClient = mockHttpClient;
    sharedComm.agentUrl = HttpUrl.parse("http://my-agent.url");
    sharedComm.createRemaining(config);
    Sampler sampler = mock(Sampler.class);

    Writer writer =
        WriterFactory.createWriter(
            config, sharedComm, sampler, null, HealthMetrics.NO_OP, configuredType);

    List<Class<?>> expectedApiClasses =
        expectedApiClass != null ? singletonList(expectedApiClass) : null;
    Collection<RemoteApi> apis;
    List<Class<?>> apiClasses;
    if (expectedApiClasses != null) {
      apis = ((RemoteWriter) writer).getApis();
      apiClasses = apis.stream().map(Object::getClass).collect(Collectors.toList());
    } else {
      apis = java.util.Collections.emptyList();
      apiClasses = java.util.Collections.emptyList();
    }

    assertEquals(expectedWriterClass, writer.getClass());
    assertTrue(expectedApiClasses == null || apiClasses.equals(expectedApiClasses));
    assertTrue(
        expectedApiClasses == null
            || apis.stream().allMatch(api -> api.isCompressionEnabled() == isCompressionEnabled));
  }

  @TableTest({
    "scenario                        | configuredType | agentRunning | hasEvpProxy | isLlmObsAgentlessEnabled | expectedWriterClass                        | expectedLlmObsApiClass                            ",
    "evp proxy not agentless         | DDIntakeWriter | true         | true        | false                    | datadog.trace.common.writer.DDIntakeWriter | datadog.trace.common.writer.ddintake.DDEvpProxyApi",
    "no evp not agentless            | DDIntakeWriter | true         | false       | false                    | datadog.trace.common.writer.DDIntakeWriter | datadog.trace.common.writer.ddintake.DDIntakeApi  ",
    "agent not running not agentless | DDIntakeWriter | false        | false       | false                    | datadog.trace.common.writer.DDIntakeWriter | datadog.trace.common.writer.ddintake.DDIntakeApi  ",
    "evp proxy agentless             | DDIntakeWriter | true         | true        | true                     | datadog.trace.common.writer.DDIntakeWriter | datadog.trace.common.writer.ddintake.DDIntakeApi  ",
    "no evp agentless                | DDIntakeWriter | true         | false       | true                     | datadog.trace.common.writer.DDIntakeWriter | datadog.trace.common.writer.ddintake.DDIntakeApi  ",
    "agent not running agentless     | DDIntakeWriter | false        | false       | true                     | datadog.trace.common.writer.DDIntakeWriter | datadog.trace.common.writer.ddintake.DDIntakeApi  "
  })
  void testWriterCreationForLlmObservability(
      String configuredType,
      boolean agentRunning,
      boolean hasEvpProxy,
      boolean isLlmObsAgentlessEnabled,
      Class<?> expectedWriterClass,
      Class<?> expectedLlmObsApiClass)
      throws Exception {
    Config config = mock(Config.class);
    when(config.getApiKey()).thenReturn("my-api-key");
    when(config.getAgentUrl()).thenReturn("http://my-agent.url");
    //noinspection unchecked
    doReturn(Prioritization.FAST_LANE)
        .when(config)
        .getEnumValue(
            eq(PRIORITIZATION_TYPE),
            (Class<Prioritization>) any(Class.class),
            any(Prioritization.class));
    when(config.isTracerMetricsEnabled()).thenReturn(true);
    when(config.isLlmObsEnabled()).thenReturn(true);
    when(config.isLlmObsAgentlessEnabled()).thenReturn(isLlmObsAgentlessEnabled);

    // Mock agent info response
    Response response;
    if (agentRunning) {
      response = buildHttpResponse(hasEvpProxy, true, HttpUrl.parse("http://my-agent.url/info"));
    } else {
      response = buildHttpResponseNotOk(HttpUrl.parse("http://my-agent.url/info"));
    }

    // Mock HTTP client that simulates delayed response for async feature discovery
    Call mockCall = mock(Call.class);
    OkHttpClient mockHttpClient = mock(OkHttpClient.class);
    when(mockCall.execute())
        .thenAnswer(
            inv -> {
              // Add a delay
              Thread.sleep(400);
              return response;
            });
    when(mockHttpClient.newCall(any(Request.class))).thenReturn(mockCall);

    // Create SharedCommunicationObjects with mocked HTTP client
    SharedCommunicationObjects sharedComm = new SharedCommunicationObjects();
    sharedComm.agentHttpClient = mockHttpClient;
    sharedComm.agentUrl = HttpUrl.parse("http://my-agent.url");
    sharedComm.createRemaining(config);
    Sampler sampler = mock(Sampler.class);

    Writer writer =
        WriterFactory.createWriter(
            config, sharedComm, sampler, null, HealthMetrics.NO_OP, configuredType);
    List<Class<?>> llmObsApiClasses =
        ((RemoteWriter) writer)
            .getApis().stream()
                .filter(
                    api -> {
                      try {
                        Field trackTypeField = api.getClass().getDeclaredField("trackType");
                        trackTypeField.setAccessible(true);
                        return trackTypeField.get(api) == TrackType.LLMOBS;
                      } catch (Exception e) {
                        return false;
                      }
                    })
                .map(Object::getClass)
                .collect(Collectors.toList());

    assertEquals(expectedWriterClass, writer.getClass());
    assertEquals(singletonList(expectedLlmObsApiClass), llmObsApiClasses);
  }

  @TableTest({
    "scenario     | protocol      | compression | endpoint                               | expectedSenderClass                           | expectedUrl                                                                             | expectedGzip",
    "http no gzip | HTTP_PROTOBUF | NONE        | 'http://otel-collector:4318/v1/traces' | datadog.trace.core.otlp.common.OtlpHttpSender | 'http://otel-collector:4318/v1/traces'                                                  | false       ",
    "http gzip    | HTTP_PROTOBUF | GZIP        | 'http://otel-collector:4318/v1/traces' | datadog.trace.core.otlp.common.OtlpHttpSender | 'http://otel-collector:4318/v1/traces'                                                  | true        ",
    "grpc no gzip | GRPC          | NONE        | 'http://otel-collector:4317'           | datadog.trace.core.otlp.common.OtlpGrpcSender | 'http://otel-collector:4317/opentelemetry.proto.collector.trace.v1.TraceService/Export' | false       "
  })
  void testWriterCreationForOtlpWriter(
      OtlpConfig.Protocol protocol,
      OtlpConfig.Compression compression,
      String endpoint,
      Class<?> expectedSenderClass,
      String expectedUrl,
      boolean expectedGzip)
      throws Exception {
    Config config = mock(Config.class);
    Map<String, String> headers = new HashMap<>();
    headers.put("api-key", "secret");

    when(config.getTraceFlushIntervalSeconds()).thenReturn(1.0f);
    when(config.getOtlpTracesEndpoint()).thenReturn(endpoint);
    when(config.getOtlpTracesHeaders()).thenReturn(headers);
    when(config.getOtlpTracesProtocol()).thenReturn(protocol);
    when(config.getOtlpTracesCompression()).thenReturn(compression);
    when(config.getOtlpTracesTimeout()).thenReturn(5000);

    // OTLP branch in WriterFactory does not consult sharedComm or sampler, so nulls are safe here.
    Writer writer =
        WriterFactory.createWriter(config, null, null, null, HealthMetrics.NO_OP, "OtlpWriter");
    Object sender = readField(writer, "sender");

    assertEquals(OtlpWriter.class, writer.getClass());
    assertEquals(expectedSenderClass, sender.getClass());
    assertEquals(expectedUrl, readField(sender, "url").toString());
    assertEquals(headers, readField(sender, "headers"));
    assertEquals(expectedGzip, readField(sender, "gzip"));

    writer.close();
  }

  private static Response buildHttpResponse(
      boolean hasEvpProxy, boolean evpProxySupportsCompression, HttpUrl agentUrl) {
    List<String> endpoints = new ArrayList<>();
    if (hasEvpProxy && evpProxySupportsCompression) {
      endpoints.add(DDAgentFeaturesDiscovery.V4_EVP_PROXY_ENDPOINT);
    } else if (hasEvpProxy) {
      endpoints.add(DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT);
    } else {
      endpoints.add(DDAgentFeaturesDiscovery.V04_ENDPOINT);
    }

    StringBuilder endpointsJson = new StringBuilder("[");
    for (int i = 0; i < endpoints.size(); i++) {
      if (i > 0) endpointsJson.append(",");
      endpointsJson.append("\"").append(endpoints.get(i)).append("\"");
    }
    endpointsJson.append("]");
    String json = "{\"version\":\"7.40.0\",\"endpoints\":" + endpointsJson + "}";

    return new Response.Builder()
        .code(200)
        .message("OK")
        .protocol(Protocol.HTTP_1_1)
        .request(new Request.Builder().url(agentUrl.resolve("/info")).build())
        .body(ResponseBody.create(MediaType.parse("application/json"), json))
        .build();
  }

  private static Response buildHttpResponseNotOk(HttpUrl agentUrl) {
    return new Response.Builder()
        .code(500)
        .message("ERROR")
        .protocol(Protocol.HTTP_1_1)
        .request(new Request.Builder().url(agentUrl.resolve("/info")).build())
        .body(ResponseBody.create(MediaType.parse("application/json"), ""))
        .build();
  }

  private static Object readField(Object instance, String fieldName) throws Exception {
    Field field = instance.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(instance);
  }
}
