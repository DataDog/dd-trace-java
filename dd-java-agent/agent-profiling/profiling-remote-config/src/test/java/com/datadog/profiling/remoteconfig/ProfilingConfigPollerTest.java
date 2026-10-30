package com.datadog.profiling.remoteconfig;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.api.profiling.ProfilingEnablement;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tabletest.junit.TableTest;

@ExtendWith(MockitoExtension.class)
class ProfilingConfigPollerTest {
  private static final String ENV = "staging";
  private static final String SERVICE = "my-app";

  @Mock private Config config;
  @Mock private SharedCommunicationObjects sco;
  @Mock private ConfigurationPoller poller;
  @Mock private ProfilingConfigHandler handler;
  @Mock private PollingRateHinter hinter;
  @Captor private ArgumentCaptor<ProductListener> listenerCaptor;
  @Captor private ArgumentCaptor<Map<String, String>> configsCaptor;

  private ProfilingConfigPoller profilingConfigPoller;

  @BeforeEach
  void setUp() {
    profilingConfigPoller = new ProfilingConfigPoller(handler);
  }

  @TableTest({
    "Scenario | Enablement | Expected",
    "armed    | ARMED      | true    ",
    "auto     | AUTO       | false   ",
    "enabled  | ENABLED    | false   ",
    "injected | INJECTED   | false   ",
    "disabled | DISABLED   | false   "
  })
  void enabledOnlyForArmedEnablement(ProfilingEnablement enablement, boolean expected) {
    when(config.getProfilingEnablement()).thenReturn(enablement);

    assertEquals(expected, ProfilingConfigPoller.isEnabled(config));
  }

  @Test
  void remoteConfigDisabled() {
    when(sco.configurationPoller(config)).thenReturn(null);

    profilingConfigPoller.start(config, sco);
    profilingConfigPoller.stop();

    verifyNoInteractions(handler);
  }

  @Test
  void subscribesToApmProfilingOnly() {
    startAndCaptureListener();

    verify(poller, never()).addCapabilities(anyLong());
    verify(poller, never()).start();
  }

  @Test
  void commitPassesAllAcceptedConfigs() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("a", 1), hinter);
    listener.accept(configKey("b"), targeted("b", 2), hinter);
    listener.commit(hinter);

    Map<String, String> expected = new HashMap<>();
    expected.put("a", payload("a", 1));
    expected.put("b", payload("b", 2));
    assertEquals(expected, committedConfigs());
  }

  @Test
  void acceptReplacesConfigWithSameId() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("v", 1), hinter);
    listener.accept(configKey("a"), targeted("v", 2), hinter);
    listener.commit(hinter);

    Map<String, String> expected = new HashMap<>();
    expected.put("a", payload("v", 2));
    assertEquals(expected, committedConfigs());
  }

  @Test
  void configNotTargetingThisApplicationIsIgnored() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("a", 1), hinter);
    listener.accept(
        configKey("other"), json("{\"targets\":[{\"service\":\"other\"}]}"), hinter);
    listener.accept(configKey("untargeted"), json("{\"quotas\":[]}"), hinter);
    listener.commit(hinter);

    Map<String, String> expected = new HashMap<>();
    expected.put("a", payload("a", 1));
    assertEquals(expected, committedConfigs());
  }

  @Test
  void configNoLongerTargetingThisApplicationIsDropped() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("a", 1), hinter);
    listener.accept(configKey("a"), json("{\"targets\":[{\"env\":\"prod\"}]}"), hinter);
    listener.commit(hinter);

    assertEquals(emptyMap(), committedConfigs());
  }

  @Test
  void removedConfigIsNotCommitted() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("a", 1), hinter);
    listener.accept(configKey("b"), targeted("b", 2), hinter);
    listener.remove(configKey("a"), hinter);
    listener.commit(hinter);

    Map<String, String> expected = new HashMap<>();
    expected.put("b", payload("b", 2));
    assertEquals(expected, committedConfigs());
  }

  @Test
  void removingAllConfigsCommitsEmptyMap() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("a", 1), hinter);
    listener.remove(configKey("a"), hinter);
    listener.commit(hinter);

    assertEquals(emptyMap(), committedConfigs());
  }

  @Test
  void committedConfigsAreAnUnmodifiableSnapshot() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("a", 1), hinter);
    listener.commit(hinter);
    Map<String, String> committed = committedConfigs();
    listener.accept(configKey("b"), targeted("b", 2), hinter);

    assertEquals(1, committed.size());
    assertThrows(UnsupportedOperationException.class, () -> committed.put("c", "{}"));
  }

  @Test
  void handlerFailureDoesNotPropagate() throws Exception {
    ProductListener listener = startAndCaptureListener();
    doThrow(new IllegalStateException("boom")).when(handler).onConfigsChanged(any(), any());

    listener.accept(configKey("a"), targeted("a", 1), hinter);

    assertDoesNotThrow(() -> listener.commit(hinter));
  }

  @Test
  void stopUnsubscribesWithoutStoppingSharedPoller() {
    startAndCaptureListener();

    profilingConfigPoller.stop();
    profilingConfigPoller.stop();

    verify(poller).removeListeners(Product.DEBUG);
    verify(poller, never()).stop();
  }

  @Test
  void noopHandlerAcceptsConfigs() {
    assertDoesNotThrow(() -> ProfilingConfigHandler.NOOP.onConfigsChanged(null, emptyMap()));
  }

  private ProductListener startAndCaptureListener() {
    when(sco.configurationPoller(config)).thenReturn(poller);
    when(config.getEnv()).thenReturn(ENV);
    when(config.getServiceName()).thenReturn(SERVICE);
    profilingConfigPoller.start(config, sco);
    verify(poller).addListener(eq(Product.DEBUG), listenerCaptor.capture());
    return listenerCaptor.getValue();
  }

  private Map<String, String> committedConfigs() {
    verify(handler).onConfigsChanged(any(), configsCaptor.capture());
    return configsCaptor.getValue();
  }

  private static ConfigKey configKey(String configId) {
    ConfigKey key = mock(ConfigKey.class);
    when(key.getConfigId()).thenReturn(configId);
    return key;
  }

  private static String payload(String key, int value) {
    return "{\""
        + key
        + "\":"
        + value
        + ",\"targets\":[{\"env\":\""
        + ENV
        + "\",\"service\":\""
        + SERVICE
        + "\"}]}";
  }

  private static byte[] targeted(String key, int value) {
    return json(payload(key, value));
  }

  private static byte[] json(String json) {
    return json.getBytes(UTF_8);
  }
}
