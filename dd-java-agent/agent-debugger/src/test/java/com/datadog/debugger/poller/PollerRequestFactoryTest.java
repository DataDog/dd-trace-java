package com.datadog.debugger.poller;

import static com.datadog.debugger.poller.PollerRequestFactory.HEADER_DD_API_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.MediaType;
import okhttp3.Request;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PollerRequestFactoryTest {
  private static final String API_URL = "https://app.datadoghq.com/api/v2/debugger";
  // the implementation uses a configured object mapper, but that shouldn't make a difference in
  // these tests
  private static final Moshi MOSHI = new Moshi.Builder().build();

  @Mock Config config;

  @Test
  void newRemoteConfigurationRequest() throws IOException {
    String runtimeId = UUID.randomUUID().toString();
    lenient().when(config.getRuntimeId()).thenReturn(runtimeId);
    when(config.getServiceName()).thenReturn("test-service");
    when(config.getEnv()).thenReturn("test-service-env");
    when(config.getVersion()).thenReturn("test-service-version");

    String agentUrl = "http://127.0.0.1:8126/v0.6/config";
    Request request = PollerRequestFactory.newConfigurationRequest(config, agentUrl, MOSHI);

    assertFalse(request.isHttps());
    assertEquals("POST", request.method());
    assertEquals(agentUrl, request.url().toString());

    assertNull(request.header(HEADER_DD_API_KEY));
    assertNull(request.header(PollerRequestFactory.HEADER_DEBUGGER_TRACKING_ID));

    assertNotNull(request.body());
    final Buffer buffer = new Buffer();
    request.body().writeTo(buffer);
    assertEquals(MediaType.parse("application/json; charset=utf-8"), request.body().contentType());

    JsonAdapter<Map<String, Object>> adapter = MoshiHelper.createGenericAdapter();
    Map<String, Object> jsonMap = adapter.fromJson(buffer.buffer());
    Map<String, Object> clientMap = (Map<String, Object>) jsonMap.get("client");
    assertEquals(runtimeId, clientMap.get("id"));
    assertEquals("live-debugger-agent", clientMap.get("name"));
    assertTrue((Boolean) clientMap.get("is_tracer"));
    assertEquals("LIVE_DEBUGGING", ((List<String>) clientMap.get("products")).get(0));
    // TODO: can we set a mock version in tests? SnapshotSink also uses null when testing the tags
    assertNull(clientMap.get("version"));
    assertEquals("{}", clientMap.get("state").toString());

    Map<String, Object> tracerClientJson = (Map<String, Object>) clientMap.get("client_tracer");
    assertEquals(runtimeId, tracerClientJson.get("runtime_id"));
    assertNull(tracerClientJson.get("tracer_version"));
    assertEquals("test-service", tracerClientJson.get("service"));
    assertEquals("test-service-env", tracerClientJson.get("env"));
    assertEquals("test-service-version", tracerClientJson.get("app_version"));
    assertEquals("jvm", tracerClientJson.get("language"));
  }

  @Test
  public void remoteConfigRequestsHaveTrackingIdWhenRuntimeIdIsNotSet() throws IOException {
    when(config.getRuntimeId()).thenReturn(null);

    Request request = PollerRequestFactory.newConfigurationRequest(config, API_URL, MOSHI);

    assertNotNull(request.body());
    final Buffer buffer = new Buffer();
    request.body().writeTo(buffer);
    assertEquals(MediaType.parse("application/json; charset=utf-8"), request.body().contentType());

    JsonAdapter<Map<String, Object>> adapter = MoshiHelper.createGenericAdapter();
    Map<String, Object> jsonMap = adapter.fromJson(buffer.buffer());
    Map<String, Object> clientMap = (Map<String, Object>) jsonMap.get("client");
    String clientId = (String) clientMap.get("id");
    assertEquals(clientId, UUID.fromString(clientId).toString(), "client.id is not a valid UUID");

    Map<String, Object> tracerClientMap = (Map<String, Object>) clientMap.get("client_tracer");
    String trackingId = (String) tracerClientMap.get("runtime_id");
    assertEquals(
        trackingId,
        UUID.fromString(trackingId).toString(),
        "client_tracer.runtime_id is not a valid UUID");
  }
}
