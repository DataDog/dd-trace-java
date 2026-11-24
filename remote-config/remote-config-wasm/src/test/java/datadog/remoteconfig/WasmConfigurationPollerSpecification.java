package datadog.remoteconfig;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import datadog.trace.util.AgentTaskScheduler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * End-to-end tests of the WASM-backed poller: a local agent-mode {@code /v0.7/config} endpoint, the
 * real reactor, and the shared listener model, driven one poll at a time like {@code
 * DefaultConfigurationPollerSpecification}.
 */
@WithConfig(key = "service", value = "my_service")
@WithConfig(key = "env", value = "my_env")
class WasmConfigurationPollerSpecification extends DDJavaSpecification {
  private static final String CONFIG_PATH = "datadog/2/APM_TRACING/poc/config";
  private static final String CONTENT_1 = "{\"sample_rate\":0.1}";
  private static final String CONTENT_2 = "{\"sample_rate\":0.2}";

  private final AgentTaskScheduler scheduler = mock(AgentTaskScheduler.class);

  @SuppressWarnings("unchecked")
  private final AgentTaskScheduler.Scheduled<ConfigurationPoller> scheduled =
      mock(AgentTaskScheduler.Scheduled.class);

  private HttpServer server;
  private final Deque<String> responses = new ArrayDeque<>();
  private final List<JSONObject> requests = new ArrayList<>();
  private final AtomicInteger requestCounter = new AtomicInteger();

  private WasmConfigurationPoller poller;
  private AgentTaskScheduler.Task<ConfigurationPoller> task;
  private String url;

  @BeforeEach
  @SuppressWarnings("unused")
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v0.7/config",
        exchange -> {
          ByteArrayOutputStream body = new ByteArrayOutputStream();
          byte[] buffer = new byte[4096];
          for (int read; (read = exchange.getRequestBody().read(buffer)) >= 0; ) {
            body.write(buffer, 0, read);
          }
          requests.add(new JSONObject(new String(body.toByteArray(), UTF_8)));
          String response = responses.isEmpty() ? "{}" : responses.poll();
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
          requestCounter.incrementAndGet();
        });
    server.start();
    url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v0.7/config";
  }

  @AfterEach
  @SuppressWarnings("unused")
  void teardown() {
    if (poller != null) {
      poller.stop();
    }
    server.stop(0);
  }

  private void startNewPoller(ProductListener listener) {
    poller =
        new WasmConfigurationPoller(
            datadog.trace.api.Config.get(),
            "0.0.0",
            "",
            "",
            () -> url,
            () -> new okhttp3.OkHttpClient(),
            scheduler);
    if (listener != null) {
      poller.addListener(Product.APM_TRACING, listener);
    }
    when(scheduler.scheduleAtFixedRate(any(), eq(poller), eq(0L), eq(5000L), eq(MILLISECONDS)))
        .thenAnswer(invocation -> {
          task = invocation.getArgument(0);
          return scheduled;
        });
    poller.start();
    assertNotNull(task);
  }

  private void poll() {
    task.run(poller);
  }

  @Test
  void issuesNoRequestIfThereAreNoSubscriptions() {
    startNewPoller(null);
    poll();
    assertEquals(0, requests.size());
  }

  @Test
  void deliversChangesAcknowledgesAndFiresEndListener() throws Exception {
    ProductListener listener = mock(ProductListener.class);
    ConfigurationEndListener endListener = mock(ConfigurationEndListener.class);
    startNewPoller(listener);
    poller.addConfigurationEndListener(endListener);

    responses.add(response(1, CONTENT_1));
    responses.add(response(2, CONTENT_2));
    poll();
    poll();

    ArgumentCaptor<ParsedConfigKey> key = ArgumentCaptor.forClass(ParsedConfigKey.class);
    ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
    verify(listener, times(2)).accept(key.capture(), content.capture(), any());
    assertArrayEquals(CONTENT_1.getBytes(UTF_8), content.getAllValues().get(0));
    assertArrayEquals(CONTENT_2.getBytes(UTF_8), content.getAllValues().get(1));
    assertEquals(Product.APM_TRACING, key.getValue().getProduct());
    assertEquals("poc", key.getAllValues().get(0).getConfigId());
    verify(listener, times(2)).commit(any());
    verify(endListener, times(2)).onConfigurationEnd();

    JSONObject firstRequest = requests.get(0);
    assertEquals(
        "java",
        firstRequest.getJSONObject("client").getJSONObject("client_tracer").getString("language"));
    assertEquals(
        "my_service",
        firstRequest.getJSONObject("client").getJSONObject("client_tracer").getString("service"));
    JSONArray products = firstRequest.getJSONObject("client").getJSONArray("products");
    assertEquals(1, products.length());
    assertEquals("APM_TRACING", products.getString(0));

    // the first poll's add was acknowledged as applied
    JSONArray configStates =
        requests
            .get(1)
            .getJSONObject("client")
            .getJSONObject("state")
            .getJSONArray("config_states");
    assertEquals(1, configStates.length());
    JSONObject configState = configStates.getJSONObject(0);
    assertEquals(2, configState.getInt("apply_state"));
  }

  @Test
  void reportsFailedApplyToBackend() throws Exception {
    ProductListener listener = mock(ProductListener.class);
    doAnswerWithException(listener);
    startNewPoller(listener);

    responses.add(response(1, CONTENT_1));
    responses.add(response(2, CONTENT_2));
    poll();
    poll();

    // an errored apply is reported to the backend and the change is retried on the next poll
    verify(listener, times(2)).accept(any(), any(), any());
    JSONArray configStates =
        requests
            .get(1)
            .getJSONObject("client")
            .getJSONObject("state")
            .getJSONArray("config_states");
    JSONObject configState = configStates.getJSONObject(0);
    assertEquals(3, configState.getInt("apply_state"));
    assertTrue(configState.getString("apply_error").contains("wasm apply failure"));
  }

  @Test
  void removesConfigWhenItDisappears() throws Exception {
    ProductListener listener = mock(ProductListener.class);
    startNewPoller(listener);

    responses.add(response(1, CONTENT_1));
    responses.add(response(2, null));
    poll();
    poll();

    verify(listener, times(1)).accept(any(), any(), any());
    verify(listener, times(1)).remove(any(ParsedConfigKey.class), any());
    verify(listener, times(2)).commit(any());
    assertFalse(requests.isEmpty());
  }

  @Test
  void configKeyListenerReceivesOnlyItsChanges() throws Exception {
    ProductListener productListener = mock(ProductListener.class);
    ProductListener configListener = mock(ProductListener.class);
    startNewPoller(productListener);
    poller.addListener(Product.APM_TRACING, "poc", configListener);

    responses.add(response(1, CONTENT_1));
    poll();

    verify(productListener, times(1)).accept(any(), any(), any());
    verify(configListener, times(1)).accept(any(ParsedConfigKey.class), any(), any());
  }

  @Test
  void recreatesClientWhenProductsChange() throws Exception {
    ProductListener listener = mock(ProductListener.class);
    ProductListener secondListener = mock(ProductListener.class);
    startNewPoller(listener);

    responses.add(response(1, CONTENT_1));
    poll();

    poller.addListener(Product.ASM_DD, secondListener);
    responses.add(response(2, CONTENT_2));
    poll();

    verify(listener, times(2)).accept(any(), any(), any());
    JSONArray products = requests.get(1).getJSONObject("client").getJSONArray("products");
    assertEquals(2, products.length());
    assertTrue(products.toString().contains("ASM_DD"));
    assertTrue(products.toString().contains("APM_TRACING"));
  }

  @Test
  void ignoresConfigChangesForUnsubscribedProducts() throws Exception {
    ProductListener listener = mock(ProductListener.class);
    startNewPoller(listener);

    // the backend returns a config for a product nobody subscribed to, then a subscribed one
    responses.add(responseForProduct("datadog/2/ASM/poc/config", 1, CONTENT_1));
    responses.add(response(2, CONTENT_2));
    poll();
    poll();

    // only the subscribed product's change is delivered
    ArgumentCaptor<ParsedConfigKey> key = ArgumentCaptor.forClass(ParsedConfigKey.class);
    verify(listener, times(1)).accept(key.capture(), any(), any());
    assertEquals(Product.APM_TRACING, key.getValue().getProduct());
  }

  @Test
  void turnsTransportFailuresIntoPollErrors() throws Exception {
    ProductListener listener = mock(ProductListener.class);
    startNewPoller(listener);

    // drop the connection instead of answering
    server.removeContext("/v0.7/config");
    server.createContext(
        "/v0.7/config",
        exchange -> {
          byte[] buffer = new byte[4096];
          for (int read; (read = exchange.getRequestBody().read(buffer)) >= 0; ) {
            // drain only, no response
          }
          exchange.close();
        });
    poll();

    verify(listener, times(0)).accept(any(), any(), any());
  }

  @Test
  void recreatesClientWhenCapabilitiesChange() throws Exception {
    ProductListener listener = mock(ProductListener.class);
    startNewPoller(listener);

    responses.add(response(1, CONTENT_1));
    responses.add(response(2, CONTENT_2));
    poll();

    poller.addCapabilities(Capabilities.CAPABILITY_APM_TRACING_SAMPLE_RATE);
    poll();

    // the client was recreated and the new capability is advertised on the wire
    JSONArray capabilities = requests.get(1).getJSONObject("client").getJSONArray("capabilities");
    long advertised = 0;
    for (int i = 0; i < capabilities.length(); i++) {
      advertised |= (long) capabilities.getInt(i) << ((capabilities.length() - i - 1) * 8);
    }
    assertEquals(
        Capabilities.CAPABILITY_APM_TRACING_SAMPLE_RATE,
        advertised & Capabilities.CAPABILITY_APM_TRACING_SAMPLE_RATE);
  }

  @SuppressWarnings("unchecked")
  private static void doAnswerWithException(ProductListener listener) throws Exception {
    org.mockito.Mockito.doAnswer(
            invocation -> {
              throw new IOException("wasm apply failure");
            })
        .when(listener)
        .accept(any(), any(), any());
  }

  /** Builds an agent-mode /v0.7/config response like the cross-host conformance suite. */
  private static String response(int version, String content) {
    return responseForProduct(CONFIG_PATH, version, content);
  }

  private static String responseForProduct(String path, int version, String content) {
    JSONObject targets =
        new JSONObject()
            .put(
                "signed",
                new JSONObject()
                    .put("custom", new JSONObject().put("opaque_backend_state", "wasm-test"))
                    .put(
                        "targets",
                        content == null
                            ? new JSONObject()
                            : new JSONObject()
                                .put(
                                    path,
                                    new JSONObject()
                                        .put("custom", new JSONObject().put("v", version))
                                        .put(
                                            "hashes",
                                            new JSONObject().put("sha256", sha256Hex(content)))
                                        .put("length", content.getBytes(UTF_8).length)))
                    .put("version", version));
    return new JSONObject()
        .put("client_configs", content == null ? new JSONArray() : new JSONArray().put(path))
        .put("roots", new JSONArray())
        .put(
            "target_files",
            content == null
                ? new JSONArray()
                : new JSONArray()
                    .put(
                        new JSONObject()
                            .put("path", path)
                            .put(
                                "raw",
                                Base64.getEncoder().encodeToString(content.getBytes(UTF_8)))))
        .put("targets", Base64.getEncoder().encodeToString(targets.toString().getBytes(UTF_8)))
        .toString();
  }

  private static String sha256Hex(String content) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
