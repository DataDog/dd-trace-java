package com.datadog.profiling.remoteconfig;

import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.CPU_TIME;
import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.WALL_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datadog.profiling.remoteconfig.RemoteConfigurationStack.Entry;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.api.profiling.ProfilingEnablement;
import java.util.ArrayList;
import java.util.List;
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
  @Captor private ArgumentCaptor<RemoteConfigurationStack> stackCaptor;

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

    assertFalse(profilingConfigPoller.start(config, sco), "nothing to subscribe to");
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

    listener.accept(configKey("a"), targeted("\"cpuTime\":true"), hinter);
    listener.accept(configKey("b"), targeted("\"wallTime\":true"), hinter);
    listener.commit(hinter);

    RemoteConfigurationStack stack = committedStack();
    assertEquals(asList("a", "b"), new ArrayList<>(stack.configIds()));
    RemoteConfiguration merged = ProfilingConfigMerger.merge(stack);
    assertEquals(true, merged.get(CPU_TIME));
    assertEquals(true, merged.get(WALL_TIME));
  }

  @Test
  void acceptedConfigIsStoredWithItsFeatures() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("\"cpuTime\":true,\"wallTime\":false"), hinter);
    listener.commit(hinter);

    RemoteConfiguration stored = committedStack().lowestToHighestPriority().get(0).configuration();
    assertEquals(true, stored.get(CPU_TIME));
    assertEquals(false, stored.get(WALL_TIME));
  }

  @Test
  void acceptedConfigIsStoredWithThePriorityOfItsTargets() throws Exception {
    ProductListener listener = startAndCaptureListener();

    // the greater config id has the lower priority
    listener.accept(
        configKey("a"),
        json("{\"profilers\":{\"cpuTime\":true},\"targets\":[{\"service\":\"my-app\"}]}"),
        hinter);
    listener.accept(
        configKey("z"),
        json("{\"profilers\":{\"cpuTime\":false},\"targets\":[{\"env\":\"staging\"}]}"),
        hinter);
    listener.accept(
        configKey("y"), json("{\"profilers\":{\"cpuTime\":false},\"targets\":[{}]}"), hinter);
    listener.commit(hinter);

    RemoteConfigurationStack stack = committedStack();
    List<Entry> entries = stack.lowestToHighestPriority();
    assertEquals(asList("y", "z", "a"), configIds(entries));
    assertEquals(asList(0, 2001, 3001), priorities(entries));
    assertEquals(true, ProfilingConfigMerger.merge(stack).get(CPU_TIME));
  }

  @Test
  void configWithoutProfilersIsStoredWithoutFeatures() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), json("{\"targets\":[{\"service\":\"my-app\"}]}"), hinter);
    listener.commit(hinter);

    RemoteConfigurationStack stack = committedStack();
    assertTrue(stack.contains("a"));
    assertTrue(ProfilingConfigMerger.merge(stack).isEmpty());
  }

  @Test
  void acceptReplacesConfigWithSameId() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("\"cpuTime\":true"), hinter);
    listener.accept(configKey("a"), targeted("\"cpuTime\":false"), hinter);
    listener.commit(hinter);

    RemoteConfigurationStack stack = committedStack();
    assertEquals(1, stack.lowestToHighestPriority().size());
    assertEquals(false, ProfilingConfigMerger.merge(stack).get(CPU_TIME));
  }

  @Test
  void configNotTargetingThisApplicationIsIgnored() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("\"cpuTime\":true"), hinter);
    listener.accept(
        configKey("other"),
        json("{\"profilers\":{\"cpuTime\":false},\"targets\":[{\"service\":\"other\"}]}"),
        hinter);
    listener.accept(configKey("untargeted"), json("{\"profilers\":{\"cpuTime\":false}}"), hinter);
    listener.commit(hinter);

    RemoteConfigurationStack stack = committedStack();
    assertEquals(asList("a"), new ArrayList<>(stack.configIds()));
    assertEquals(true, ProfilingConfigMerger.merge(stack).get(CPU_TIME));
  }

  @Test
  void configForAnotherServiceOrEnvIsRejected() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(
        configKey("other-service"),
        json("{\"profilers\":{\"cpuTime\":true},\"targets\":[{\"service\":\"other-app\"}]}"),
        hinter);
    listener.accept(
        configKey("other-env"),
        json("{\"profilers\":{\"cpuTime\":true},\"targets\":[{\"env\":\"prod\"}]}"),
        hinter);
    listener.accept(
        configKey("other-both"),
        json(
            "{\"profilers\":{\"cpuTime\":true},"
                + "\"targets\":[{\"env\":\"prod\",\"service\":\"other-app\"}]}"),
        hinter);
    listener.commit(hinter);

    assertTrue(committedStack().isEmpty());
  }

  @Test
  void configWithOnlyMalformedTargetsIsRejected() throws Exception {
    ProductListener listener = startAndCaptureListener();

    // a target that isn't valid must not be taken for one that matches every service
    listener.accept(
        configKey("service-not-a-string"),
        json("{\"profilers\":{\"cpuTime\":true},\"targets\":[{\"service\":123}]}"),
        hinter);
    listener.accept(
        configKey("tags-not-an-array"),
        json("{\"profilers\":{\"cpuTime\":true},\"targets\":[{\"tags\":\"x\"}]}"),
        hinter);
    listener.commit(hinter);

    assertTrue(committedStack().isEmpty());
  }

  @Test
  void configMatchingThisServiceAfterNormalizationIsAccepted() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(
        configKey("a"),
        json("{\"profilers\":{\"cpuTime\":true},\"targets\":[{\"service\":\"MY-APP\"}]}"),
        hinter);
    listener.commit(hinter);

    assertTrue(committedStack().contains("a"));
  }

  @Test
  void configNoLongerTargetingThisApplicationIsDropped() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("\"cpuTime\":true"), hinter);
    listener.accept(configKey("a"), json("{\"targets\":[{\"env\":\"prod\"}]}"), hinter);
    listener.commit(hinter);

    assertTrue(committedStack().isEmpty());
  }

  @Test
  void removedConfigIsNotCommitted() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("\"cpuTime\":true"), hinter);
    listener.accept(configKey("b"), targeted("\"wallTime\":true"), hinter);
    listener.remove(configKey("a"), hinter);
    listener.commit(hinter);

    RemoteConfigurationStack stack = committedStack();
    assertEquals(asList("b"), new ArrayList<>(stack.configIds()));
    assertFalse(stack.contains("a"));
  }

  @Test
  void removingAllConfigsCommitsAnEmptyStack() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("\"cpuTime\":true"), hinter);
    listener.remove(configKey("a"), hinter);
    listener.commit(hinter);

    assertTrue(committedStack().isEmpty());
  }

  @Test
  void committedStackIsASnapshot() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("\"cpuTime\":true"), hinter);
    listener.commit(hinter);
    RemoteConfigurationStack committed = committedStack();
    listener.accept(configKey("b"), targeted("\"wallTime\":true"), hinter);
    listener.remove(configKey("a"), hinter);

    assertEquals(asList("a"), new ArrayList<>(committed.configIds()));
  }

  @Test
  void handlerFailureDoesNotPropagate() throws Exception {
    ProductListener listener = startAndCaptureListener();
    doThrow(new IllegalStateException("boom")).when(handler).onConfigsChanged(any());

    listener.accept(configKey("a"), targeted("\"cpuTime\":true"), hinter);

    assertDoesNotThrow(() -> listener.commit(hinter));
  }

  @Test
  void stopUnsubscribesWithoutStoppingSharedPoller() {
    startAndCaptureListener();

    profilingConfigPoller.stop();
    profilingConfigPoller.stop();

    verify(poller).removeListeners(Product.APM_PROFILING);
    verify(poller, never()).stop();
  }

  @Test
  void noopHandlerAcceptsConfigs() {
    assertDoesNotThrow(
        () -> ProfilingConfigHandler.NOOP.onConfigsChanged(new RemoteConfigurationStack()));
  }

  private ProductListener startAndCaptureListener() {
    when(sco.configurationPoller(config)).thenReturn(poller);
    when(config.getEnv()).thenReturn(ENV);
    when(config.getServiceName()).thenReturn(SERVICE);
    assertTrue(profilingConfigPoller.start(config, sco), "subscribed");
    verify(poller).addListener(eq(Product.APM_PROFILING), listenerCaptor.capture());
    return listenerCaptor.getValue();
  }

  private RemoteConfigurationStack committedStack() {
    verify(handler).onConfigsChanged(stackCaptor.capture());
    return stackCaptor.getValue();
  }

  private static ConfigKey configKey(String configId) {
    ConfigKey key = mock(ConfigKey.class);
    when(key.getConfigId()).thenReturn(configId);
    return key;
  }

  /** A payload targeting this application that sets the given profilers. */
  private static byte[] targeted(String profilers) {
    return json(
        "{\"profilers\":{"
            + profilers
            + "},\"targets\":[{\"env\":\""
            + ENV
            + "\",\"service\":\""
            + SERVICE
            + "\"}]}");
  }

  private static byte[] json(String json) {
    return json.getBytes(UTF_8);
  }

  private static List<String> configIds(List<Entry> entries) {
    List<String> ids = new ArrayList<>();
    for (Entry entry : entries) {
      ids.add(entry.configId());
    }
    return ids;
  }

  private static List<Integer> priorities(List<Entry> entries) {
    List<Integer> priorities = new ArrayList<>();
    for (Entry entry : entries) {
      priorities.add(entry.priority());
    }
    return priorities;
  }
}
