package com.datadog.profiling.remoteconfig;

import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.CPU_TIME;
import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.WALL_TIME;
import static com.datadog.profiling.remoteconfig.TestConfigKey.configKey;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
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
import java.io.IOException;
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
    // a lower rank than a, so that both count
    listener.accept(
        configKey("b"),
        json("{\"profilers\":{\"wallTime\":true},\"targets\":[{\"service\":\"my-app\"}]}"),
        hinter);
    listener.commit(hinter);

    RemoteConfigurationStack stack = committedStack();
    assertEquals(asList("a", "b"), new ArrayList<>(stack.configIds()));
    RemoteConfiguration merged = stack.merged();
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

    // the priority doesn't depend on the config id
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
    assertEquals(true, stack.merged().get(CPU_TIME));
  }

  @Test
  void configWithoutProfilersOrSettingsIsNotAProfilingConfig() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("\"cpuTime\":true"), hinter);
    listener.accept(configKey("a"), json("{\"targets\":[{\"service\":\"my-app\"}]}"), hinter);
    listener.accept(configKey("b"), json("{\"targets\":[{\"service\":\"my-app\"}]}"), hinter);
    // settings alone don't make a profiling config either
    listener.accept(
        configKey("c"), json("{\"settings\":{},\"targets\":[{\"service\":\"my-app\"}]}"), hinter);
    listener.commit(hinter);

    assertTrue(committedStack().isEmpty());
  }

  @Test
  void unreadablePayloadReplacesTheConfigAndIsReported() throws Exception {
    ProductListener listener = startAndCaptureListener();
    listener.accept(configKey("a"), targeted("\"cpuTime\":true"), hinter);

    IOException error =
        assertThrows(
            IOException.class,
            () -> listener.accept(configKey("a"), json("{\"profilers\":{"), hinter));
    listener.commit(hinter);

    assertEquals("APM_PROFILING config a ignored: payload is not an object", error.getMessage());
    // no longer a usable config
    assertTrue(committedStack().isEmpty());
  }

  @TableTest({
    "Scenario                | Profilers",
    "profilers not an object | '[]'     ",
    "profilers null          | 'null'   ",
    "profilers a boolean     | 'true'   "
  })
  void malformedProfilersSectionDropsTheConfig(String profilers) throws Exception {
    ProductListener listener = startAndCaptureListener();
    listener.accept(configKey("a"), targeted("\"cpuTime\":true"), hinter);

    IOException error =
        assertThrows(
            IOException.class,
            () ->
                listener.accept(
                    configKey("a"),
                    json(
                        "{\"profilers\":" + profilers + ",\"targets\":[{\"service\":\"my-app\"}]}"),
                    hinter));
    listener.commit(hinter);

    assertEquals("APM_PROFILING config a ignored: profilers is not an object", error.getMessage());
    // not taken for a config that sets no feature, which would apply the local configuration
    assertTrue(committedStack().isEmpty());
  }

  @Test
  void acceptReplacesConfigWithSameId() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("\"cpuTime\":true"), hinter);
    listener.accept(configKey("a"), targeted("\"cpuTime\":false"), hinter);
    listener.commit(hinter);

    RemoteConfigurationStack stack = committedStack();
    assertEquals(1, stack.lowestToHighestPriority().size());
    assertEquals(false, stack.merged().get(CPU_TIME));
  }

  /**
   * Remote config applies a target that replaces another one with the same config id before it
   * removes the replaced one, which must not remove the new config.
   */
  @Test
  void replacedTargetWithSameConfigIdIsKeptWhenTheOldOneIsRemovedAfter() throws Exception {
    ProductListener listener = startAndCaptureListener();
    ConfigKey original = configKey("datadog", 2, "a");
    ConfigKey replacement = configKey("employee", null, "a");

    listener.accept(original, targeted("\"cpuTime\":true"), hinter);
    listener.commit(hinter);
    clearInvocations(handler);
    // the next poll, in the order the poller calls back
    listener.accept(replacement, targeted("\"cpuTime\":false"), hinter);
    listener.remove(original, hinter);
    listener.commit(hinter);

    RemoteConfigurationStack stack = committedStack();
    assertEquals(asList("a"), new ArrayList<>(stack.configIds()));
    assertTrue(stack.contains(replacement));
    assertFalse(stack.contains(original));
    assertEquals(false, stack.merged().get(CPU_TIME));
  }

  @Test
  void configWithoutTargetsIsStoredWithTheLowestPriority() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("untargeted"), json("{\"profilers\":{\"cpuTime\":false}}"), hinter);
    listener.accept(configKey("a"), targeted("\"cpuTime\":true"), hinter);
    listener.commit(hinter);

    RemoteConfigurationStack stack = committedStack();
    assertEquals(asList("untargeted", "a"), configIds(stack.lowestToHighestPriority()));
    assertEquals(true, stack.merged().get(CPU_TIME));
  }

  @Test
  void configWithOnlyMalformedTargetsIsDroppedAndReported() throws Exception {
    ProductListener listener = startAndCaptureListener();

    // a target that isn't valid must not be taken for one that matches every service
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                listener.accept(
                    configKey("service-not-a-string"),
                    json("{\"profilers\":{\"cpuTime\":true},\"targets\":[{\"service\":123}]}"),
                    hinter));
    assertEquals(
        "APM_PROFILING config service-not-a-string ignored: targets[0] is invalid; no valid targets",
        error.getMessage());
    assertThrows(
        IOException.class,
        () ->
            listener.accept(
                configKey("targets-not-an-array"),
                json("{\"profilers\":{\"cpuTime\":true},\"targets\":{}}"),
                hinter));
    listener.commit(hinter);

    assertTrue(committedStack().isEmpty());
  }

  @Test
  void invalidFieldsAreReportedWhileTheValidOnesApply() throws Exception {
    ProductListener listener = startAndCaptureListener();

    IOException error =
        assertThrows(
            IOException.class,
            () ->
                listener.accept(
                    configKey("a"),
                    json(
                        "{\"profilers\":{\"cpuTime\":true,\"wallTime\":\"yes\"},"
                            + "\"targets\":[{\"service\":\"my-app\"},{\"service\":1}]}"),
                    hinter));
    listener.commit(hinter);

    assertEquals(
        "APM_PROFILING config a applied without its invalid parts: "
            + "profilers.wallTime is not a boolean; targets[1] is invalid",
        error.getMessage());
    RemoteConfiguration stored = committedStack().lowestToHighestPriority().get(0).configuration();
    assertEquals(true, stored.get(CPU_TIME));
    assertEquals(null, stored.get(WALL_TIME));
  }

  /**
   * A config whose features are all invalid is dropped rather than taken for one that sets none,
   * which would apply the local configuration and take the place of an older config of the same
   * priority.
   */
  @Test
  void configWhoseFeaturesAreAllInvalidIsDropped() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(
        configKey("all-off"),
        targeted(
            "\"cpuTime\":false,\"wallTime\":false,\"heapObjects\":false,"
                + "\"heapSize\":false,\"allocations\":false"),
        hinter);
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                listener.accept(
                    configKey("invalid"), targeted("\"cpuTime\":\"yes\",\"wallTime\":1"), hinter));
    listener.commit(hinter);

    assertEquals(
        "APM_PROFILING config invalid ignored: "
            + "profilers.cpuTime is not a boolean; profilers.wallTime is not a boolean",
        error.getMessage());
    RemoteConfigurationStack stack = committedStack();
    assertEquals(asList("all-off"), new ArrayList<>(stack.configIds()));
    assertTrue(stack.merged().disablesEverything());
  }

  /**
   * Remote config only delivers the configs whose targets the agent matched to this application, so
   * the listener accepts them without matching the targets itself, which only rank them.
   */
  @Test
  void configDeliveredByRemoteConfigIsAcceptedWithoutMatchingItsTargets() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(
        configKey("a"),
        json(
            "{\"profilers\":{\"cpuTime\":true},"
                + "\"targets\":[{\"service\":\"not-"
                + SERVICE
                + "\",\"env\":\"not-"
                + ENV
                + "\"}]}"),
        hinter);
    listener.commit(hinter);

    assertTrue(committedStack().contains(configKey("a")));
  }

  @Test
  void configThatBecomesInvalidIsDropped() throws Exception {
    ProductListener listener = startAndCaptureListener();

    listener.accept(configKey("a"), targeted("\"cpuTime\":true"), hinter);
    assertThrows(
        IOException.class,
        () ->
            listener.accept(
                configKey("a"),
                json("{\"profilers\":{\"cpuTime\":true},\"targets\":[{\"service\":1}]}"),
                hinter));
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
    assertFalse(stack.contains(configKey("a")));
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

  private ProductListener startAndCaptureListener() {
    when(sco.configurationPoller(config)).thenReturn(poller);
    assertTrue(profilingConfigPoller.start(config, sco), "subscribed");
    verify(poller).addListener(eq(Product.APM_PROFILING), listenerCaptor.capture());
    return listenerCaptor.getValue();
  }

  private RemoteConfigurationStack committedStack() {
    verify(handler).onConfigsChanged(stackCaptor.capture());
    return stackCaptor.getValue();
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
