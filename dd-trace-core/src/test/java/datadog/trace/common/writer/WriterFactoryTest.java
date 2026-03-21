package datadog.trace.common.writer;

import static datadog.trace.api.config.TracerConfig.PRIORITIZATION_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.intake.TrackType;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.common.writer.ddintake.DDEvpProxyApi;
import datadog.trace.common.writer.ddintake.DDIntakeApi;
import datadog.trace.core.monitor.HealthMetrics;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

class WriterFactoryTest {

  @TableTest({
    "scenario                                    | configuredType                           | hasEvpProxy | evpProxySupportsCompression | isCiVisibilityAgentlessEnabled | expectedWriterClass  | isCompressionEnabled",
    "LoggingWriter                               | LoggingWriter                            | true        | false                       | true                           | LoggingWriter        | false               ",
    "PrintingWriter                              | PrintingWriter                           | true        | false                       | true                           | PrintingWriter       | false               ",
    "TraceStructureWriter                        | TraceStructureWriter                     | true        | false                       | true                           | TraceStructureWriter | false               ",
    "MultiWriter LoggingWriter PrintingWriter    | MultiWriter:LoggingWriter,PrintingWriter | true        | false                       | true                           | MultiWriter          | false               ",
    "DDIntakeWriter evp agentless                | DDIntakeWriter                           | true        | false                       | true                           | DDIntakeWriter       | true                ",
    "DDIntakeWriter evp not agentless            | DDIntakeWriter                           | true        | false                       | false                          | DDIntakeWriter       | false               ",
    "DDIntakeWriter no evp agentless             | DDIntakeWriter                           | false       | false                       | true                           | DDIntakeWriter       | true                ",
    "DDIntakeWriter no evp not agentless         | DDIntakeWriter                           | false       | false                       | false                          | DDIntakeWriter       | true                ",
    "DDAgentWriter evp agentless                 | DDAgentWriter                            | true        | false                       | true                           | DDIntakeWriter       | true                ",
    "DDAgentWriter evp not agentless             | DDAgentWriter                            | true        | false                       | false                          | DDIntakeWriter       | false               ",
    "DDAgentWriter evp compression not agentless | DDAgentWriter                            | true        | true                        | false                          | DDIntakeWriter       | true                ",
    "DDAgentWriter no evp agentless              | DDAgentWriter                            | false       | false                       | true                           | DDIntakeWriter       | true                ",
    "DDAgentWriter no evp not agentless          | DDAgentWriter                            | false       | false                       | false                          | DDAgentWriter        | false               ",
    "not-found evp agentless                     | not-found                                | true        | false                       | true                           | DDIntakeWriter       | true                ",
    "not-found evp not agentless                 | not-found                                | true        | false                       | false                          | DDIntakeWriter       | false               ",
    "not-found no evp agentless                  | not-found                                | false       | false                       | true                           | DDIntakeWriter       | true                ",
    "not-found no evp not agentless              | not-found                                | false       | false                       | false                          | DDAgentWriter        | false               "
  })
  @ParameterizedTest(name = "[{index}] {0}")
  void testWriterCreationForCiVisibility(
      String configuredType,
      boolean hasEvpProxy,
      boolean evpProxySupportsCompression,
      boolean isCiVisibilityAgentlessEnabled,
      String expectedWriterClassName,
      boolean isCompressionEnabled)
      throws Exception {
    Config config = mock(Config.class);
    when(config.getApiKey()).thenReturn("my-api-key");
    when(config.getAgentUrl()).thenReturn("http://my-agent.url");
    when(config.<Prioritization>getEnumValue(
            org.mockito.ArgumentMatchers.eq(PRIORITIZATION_TYPE),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(Prioritization.FAST_LANE);
    when(config.isTracerMetricsEnabled()).thenReturn(true);
    when(config.isCiVisibilityEnabled()).thenReturn(true);
    when(config.isCiVisibilityCodeCoverageEnabled()).thenReturn(false);

    Response response =
        buildHttpResponse(
            hasEvpProxy, evpProxySupportsCompression, HttpUrl.parse("http://my-agent.url/info"));

    Call mockCall = mock(Call.class);
    OkHttpClient mockHttpClient = mock(OkHttpClient.class);
    when(mockCall.execute())
        .thenAnswer(
            invocation -> {
              Thread.sleep(400);
              return response;
            });
    when(mockHttpClient.newCall(any(Request.class))).thenReturn(mockCall);

    SharedCommunicationObjects sharedComm = new SharedCommunicationObjects();
    sharedComm.agentHttpClient = mockHttpClient;
    sharedComm.agentUrl = HttpUrl.parse("http://my-agent.url");
    sharedComm.createRemaining(config);

    Sampler sampler = mock(Sampler.class);

    when(config.isCiVisibilityAgentlessEnabled()).thenReturn(isCiVisibilityAgentlessEnabled);

    Writer writer =
        WriterFactory.createWriter(
            config, sharedComm, sampler, null, HealthMetrics.NO_OP, configuredType);

    Class<?> expectedWriterClass = resolveWriterClass(expectedWriterClassName);
    assertEquals(expectedWriterClass, writer.getClass());

    if (writer instanceof RemoteWriter) {
      List<?> apis = new ArrayList<>(((RemoteWriter) writer).getApis());
      boolean allMatch =
          apis.stream().allMatch(api -> getCompressionEnabled(api) == isCompressionEnabled);
      assertEquals(true, allMatch);
    }
  }

  @TableTest({
    "scenario                              | configuredType | agentRunning | hasEvpProxy | isLlmObsAgentlessEnabled | expectedWriterClass | expectedLlmObsApiClass",
    "DDIntakeWriter evp not agentless      | DDIntakeWriter | true         | true        | false                    | DDIntakeWriter      | DDEvpProxyApi         ",
    "DDIntakeWriter no evp not agentless   | DDIntakeWriter | true         | false       | false                    | DDIntakeWriter      | DDIntakeApi           ",
    "DDIntakeWriter no agent not agentless | DDIntakeWriter | false        | false       | false                    | DDIntakeWriter      | DDIntakeApi           ",
    "DDIntakeWriter evp agentless          | DDIntakeWriter | true         | true        | true                     | DDIntakeWriter      | DDIntakeApi           ",
    "DDIntakeWriter no evp agentless       | DDIntakeWriter | true         | false       | true                     | DDIntakeWriter      | DDIntakeApi           ",
    "DDIntakeWriter no agent agentless     | DDIntakeWriter | false        | false       | true                     | DDIntakeWriter      | DDIntakeApi           "
  })
  @ParameterizedTest(name = "[{index}] {0}")
  void testWriterCreationForLlmObservability(
      String configuredType,
      boolean agentRunning,
      boolean hasEvpProxy,
      boolean isLlmObsAgentlessEnabled,
      String expectedWriterClassName,
      String expectedLlmObsApiClassName)
      throws Exception {
    Config config = mock(Config.class);
    when(config.getApiKey()).thenReturn("my-api-key");
    when(config.getAgentUrl()).thenReturn("http://my-agent.url");
    when(config.<Prioritization>getEnumValue(
            org.mockito.ArgumentMatchers.eq(PRIORITIZATION_TYPE),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(Prioritization.FAST_LANE);
    when(config.isTracerMetricsEnabled()).thenReturn(true);
    when(config.isLlmObsEnabled()).thenReturn(true);

    Response response;
    if (agentRunning) {
      response = buildHttpResponse(hasEvpProxy, true, HttpUrl.parse("http://my-agent.url/info"));
    } else {
      response = buildHttpResponseNotOk(HttpUrl.parse("http://my-agent.url/info"));
    }

    Call mockCall = mock(Call.class);
    OkHttpClient mockHttpClient = mock(OkHttpClient.class);
    when(mockCall.execute())
        .thenAnswer(
            invocation -> {
              Thread.sleep(400);
              return response;
            });
    when(mockHttpClient.newCall(any(Request.class))).thenReturn(mockCall);

    SharedCommunicationObjects sharedComm = new SharedCommunicationObjects();
    sharedComm.agentHttpClient = mockHttpClient;
    sharedComm.agentUrl = HttpUrl.parse("http://my-agent.url");
    sharedComm.createRemaining(config);

    Sampler sampler = mock(Sampler.class);

    when(config.isLlmObsAgentlessEnabled()).thenReturn(isLlmObsAgentlessEnabled);

    Writer writer =
        WriterFactory.createWriter(
            config, sharedComm, sampler, null, HealthMetrics.NO_OP, configuredType);

    Class<?> expectedWriterClass = resolveWriterClass(expectedWriterClassName);
    assertEquals(expectedWriterClass, writer.getClass());

    Class<?> expectedLlmObsApiClass = resolveApiClass(expectedLlmObsApiClassName);
    List<Object> llmObsApis = new ArrayList<>();
    if (writer instanceof RemoteWriter) {
      for (Object api : ((RemoteWriter) writer).getApis()) {
        try {
          Field trackTypeField = api.getClass().getDeclaredField("trackType");
          trackTypeField.setAccessible(true);
          if (trackTypeField.get(api) == TrackType.LLMOBS) {
            llmObsApis.add(api);
          }
        } catch (Exception e) {
          // not this api type
        }
      }
    }
    List<Class<?>> llmObsApiClasses =
        llmObsApis.stream().map(Object::getClass).collect(Collectors.toList());
    assertEquals(Collections.singletonList(expectedLlmObsApiClass), llmObsApiClasses);
  }

  private Response buildHttpResponse(
      boolean hasEvpProxy, boolean evpProxySupportsCompression, HttpUrl agentUrl)
      throws IOException {
    List<String> endpoints = new ArrayList<>();
    if (hasEvpProxy && evpProxySupportsCompression) {
      endpoints.add(DDAgentFeaturesDiscovery.V4_EVP_PROXY_ENDPOINT);
    } else if (hasEvpProxy) {
      endpoints.add(DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT);
    } else {
      endpoints.add(DDAgentFeaturesDiscovery.V04_ENDPOINT);
    }

    Map<String, Object> responseMap = new HashMap<>();
    responseMap.put("version", "7.40.0");
    responseMap.put("endpoints", endpoints);

    String responseBody = new ObjectMapper().writeValueAsString(responseMap);

    return new Response.Builder()
        .code(200)
        .message("OK")
        .protocol(Protocol.HTTP_1_1)
        .request(new Request.Builder().url(agentUrl.resolve("/info")).build())
        .body(ResponseBody.create(MediaType.parse("application/json"), responseBody))
        .build();
  }

  private Response buildHttpResponseNotOk(HttpUrl agentUrl) {
    return new Response.Builder()
        .code(500)
        .message("ERROR")
        .protocol(Protocol.HTTP_1_1)
        .request(new Request.Builder().url(agentUrl.resolve("/info")).build())
        .body(ResponseBody.create(MediaType.parse("text/plain"), ""))
        .build();
  }

  private static Class<?> resolveWriterClass(String name) throws ClassNotFoundException {
    switch (name.trim()) {
      case "LoggingWriter":
        return LoggingWriter.class;
      case "PrintingWriter":
        return PrintingWriter.class;
      case "TraceStructureWriter":
        return TraceStructureWriter.class;
      case "MultiWriter":
        return MultiWriter.class;
      case "DDIntakeWriter":
        return datadog.trace.common.writer.DDIntakeWriter.class;
      case "DDAgentWriter":
        return DDAgentWriter.class;
      default:
        throw new IllegalArgumentException("Unknown writer class: " + name);
    }
  }

  private static Class<?> resolveApiClass(String name) throws ClassNotFoundException {
    switch (name.trim()) {
      case "DDIntakeApi":
        return DDIntakeApi.class;
      case "DDEvpProxyApi":
        return DDEvpProxyApi.class;
      case "DDAgentApi":
        return DDAgentApi.class;
      default:
        throw new IllegalArgumentException("Unknown api class: " + name);
    }
  }

  private static boolean getCompressionEnabled(Object api) {
    try {
      java.lang.reflect.Method method = api.getClass().getMethod("isCompressionEnabled");
      return (Boolean) method.invoke(api);
    } catch (Exception e) {
      return false;
    }
  }
}
