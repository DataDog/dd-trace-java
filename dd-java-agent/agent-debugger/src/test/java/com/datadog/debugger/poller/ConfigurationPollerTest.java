package com.datadog.debugger.poller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static utils.TestHelper.getFixtureContent;

import com.datadog.debugger.agent.Configuration;
import com.datadog.debugger.agent.MetricProbe;
import com.datadog.debugger.agent.SnapshotProbe;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.util.RemoteConfigHelper;
import datadog.trace.api.Config;
import datadog.trace.util.AgentTaskScheduler;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ConfigurationPollerTest {
  public static final String URL_PATH = "/foo";
  private static final int MAX_PAYLOAD_SIZE = 1 * 1024 * 1024;
  private static final String SERVICE_NAME = "petclinic";

  @SuppressWarnings("deprecation")
  @Mock
  Config config;

  final MockWebServer server = new MockWebServer();
  HttpUrl url;

  @BeforeEach
  public void setUp() {
    url = server.url(URL_PATH);
    lenient().when(config.getDebuggerMaxPayloadSize()).thenReturn((long) MAX_PAYLOAD_SIZE);
    // use lenient here because not all tests needs this key
    lenient().when(config.getApiKey()).thenReturn("1c0ffee11c0ffee11c0ffee11c0ffee1");
    lenient().when(config.getRuntimeId()).thenReturn(UUID.randomUUID().toString());
  }

  @AfterEach
  public void tearDown() {
    try {
      server.shutdown();
    } catch (final IOException e) {
      // Looks like this happens for some unclear reason, but should not affect tests
    }
  }

  @Test
  public void receive200Response() throws IOException, URISyntaxException {
    String fixtureContent = getFixtureContent("/test_probe.json");
    String remoteConfig = RemoteConfigHelper.encode(fixtureContent, SERVICE_NAME);
    server.enqueue(new MockResponse().setResponseCode(200).setBody(remoteConfig));
    when(config.getFinalDebuggerProbeUrl()).thenReturn(server.url(URL_PATH).toString());
    when(config.getServiceName()).thenReturn(SERVICE_NAME);
    AtomicBoolean assertOk = new AtomicBoolean(false);
    ConfigurationPoller poller =
        new ConfigurationPoller(
            config,
            debuggerApp -> {
              assertGoodProbeDefinition(debuggerApp);
              assertOk.set(true);
              return true;
            });
    try {
      poller.pollDebuggerProbes(null);
      assertTrue(assertOk.get());
    } finally {
      poller.stop();
    }
  }

  @Test
  public void conditionalReceive200Response() throws IOException, URISyntaxException {
    String fixtureContent = getFixtureContent("/test_probe_conditional.json");
    String remoteConfig = RemoteConfigHelper.encode(fixtureContent, SERVICE_NAME);
    server.enqueue(new MockResponse().setResponseCode(200).setBody(remoteConfig));
    when(config.getFinalDebuggerProbeUrl()).thenReturn(server.url(URL_PATH).toString());
    when(config.getServiceName()).thenReturn(SERVICE_NAME);
    AtomicBoolean assertOk = new AtomicBoolean(false);
    ConfigurationPoller poller =
        new ConfigurationPoller(
            config,
            debuggerApp -> {
              assertGoodConditionalProbeDefinition(debuggerApp);
              assertOk.set(true);
              return true;
            });
    try {
      poller.pollDebuggerProbes(null);
      assertTrue(assertOk.get());
    } finally {
      poller.stop();
    }
  }

  @Test
  public void metricReceive200Response() throws IOException, URISyntaxException {
    String fixtureContent = getFixtureContent("/test_metric_probe.json");
    String remoteConfig = RemoteConfigHelper.encode(fixtureContent, SERVICE_NAME);
    server.enqueue(new MockResponse().setResponseCode(200).setBody(remoteConfig));
    when(config.getFinalDebuggerProbeUrl()).thenReturn(server.url(URL_PATH).toString());
    when(config.getServiceName()).thenReturn(SERVICE_NAME);
    AtomicBoolean assertOk = new AtomicBoolean(false);
    ConfigurationPoller poller =
        new ConfigurationPoller(
            config,
            debuggerApp -> {
              assertGoodMetricProbeDefinition(debuggerApp);
              assertOk.set(true);
              return true;
            });
    try {
      poller.pollDebuggerProbes(null);
      assertTrue(assertOk.get());
    } finally {
      poller.stop();
    }
  }

  @Test
  public void readFromFile() throws URISyntaxException {
    URL res = getClass().getClassLoader().getResource("test_probe2.json");
    String probeDefinitionPath = Paths.get(res.toURI()).toFile().getAbsolutePath();
    when(config.getDebuggerProbeFileLocation()).thenReturn(probeDefinitionPath);
    when(config.getFinalDebuggerProbeUrl()).thenReturn("http://localhost");
    when(config.getServiceName()).thenReturn(SERVICE_NAME);
    AtomicBoolean assertOk = new AtomicBoolean(false);
    ConfigurationPoller poller =
        new ConfigurationPoller(
            config,
            debuggerApp -> {
              assertSimpleProbeDefinition(debuggerApp);
              assertOk.set(true);
              return true;
            });
    try {
      poller.pollDebuggerProbes(null);
      assertTrue(assertOk.get());
    } finally {
      poller.stop();
    }
  }

  @Test
  public void receive500ResponseWithBody() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(500)
            .addHeader("Content-Type: application/json")
            .setBody("{\"message\": \"error\"}"));
    when(config.getFinalDebuggerProbeUrl()).thenReturn(server.url(URL_PATH).toString());
    ConfigurationPoller poller =
        new ConfigurationPoller(
            config,
            debuggerProbes -> {
              throw new AssertionError("Shouldn't reach");
            });
    try {
      poller.pollDebuggerProbes(null);
    } finally {
      poller.stop();
    }
  }

  @Test
  public void emptyUrl() {
    when(config.getFinalDebuggerProbeUrl()).thenReturn(null);
    assertThrows(IllegalArgumentException.class, () -> new ConfigurationPoller(config, null));
  }

  @Test
  public void emptyProbeUrl() {
    when(config.getFinalDebuggerProbeUrl()).thenReturn("");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ConfigurationPoller(config, debuggerProbes -> true, ""));
    assertEquals("Probe url is empty", thrown.getMessage());
  }

  @Test
  public void remoteConfiguration() throws IOException, URISyntaxException {
    String content = getFixtureContent("/tuf/remote-config.json");
    server.enqueue(new MockResponse().setResponseCode(200).setBody(content));
    when(config.getFinalDebuggerProbeUrl()).thenReturn(server.url(URL_PATH).toString());
    when(config.getServiceName()).thenReturn("debugger-demo-java");

    AtomicBoolean assertOk = new AtomicBoolean(false);
    ConfigurationPoller poller =
        new ConfigurationPoller(
            config,
            debuggerApp -> {
              assertGoodFleetProbeDefinition(debuggerApp);
              assertOk.set(true);
              return true;
            });
    poller.pollDebuggerProbes(null);
    assertTrue(assertOk.get());
  }

  @Test
  public void tooLargeAgentResponse() throws IOException, URISyntaxException {
    when(config.getFinalDebuggerProbeUrl()).thenReturn(server.url(URL_PATH).toString());
    char[] charBuffer = new char[MAX_PAYLOAD_SIZE];
    Arrays.fill(charBuffer, 'a');
    String template = getFixtureContent("/test_template_id_config.json");
    String content = String.format(template, new String(charBuffer));
    server.enqueue(new MockResponse().setResponseCode(200).setBody(content));
    ConfigurationPoller poller = new ConfigurationPoller(config, debuggerApp -> true);
    RuntimeException runtimeException =
        assertThrows(RuntimeException.class, () -> poller.pollDebuggerProbes(null));
    assertEquals(
        "Reached maximum bytes for this stream: " + MAX_PAYLOAD_SIZE,
        runtimeException.getCause().getCause().getMessage());
  }

  private static void assertSimpleProbeDefinition(Configuration configuration) {
    assertNotNull(configuration);
    Collection<SnapshotProbe> snapshotProbes = configuration.getSnapshotProbes();
    assertNotNull(snapshotProbes);
    assertEquals(1, snapshotProbes.size());
    SnapshotProbe snapshotProbe = snapshotProbes.iterator().next();
    assertEquals("123356536", snapshotProbe.getId());
    assertEquals("toString", snapshotProbe.getWhere().getMethodName());
    Collection<MetricProbe> metricProbes = configuration.getMetricProbes();
    assertEquals(1, metricProbes.size());
    MetricProbe metricProbe = metricProbes.iterator().next();
    assertEquals("123356537", metricProbe.getId());
    assertEquals("datadog.debugger.showVetList.calls", metricProbe.getMetricName());
    assertEquals(new ValueScript(42.0), metricProbe.getValue());
  }

  private static void assertGoodProbeDefinition(Configuration configuration) {
    assertNotNull(configuration);
    Collection<SnapshotProbe> snapshotProbes = configuration.getSnapshotProbes();
    assertNotNull(snapshotProbes);
    assertEquals(1, snapshotProbes.size());
    SnapshotProbe snapshotProbe = snapshotProbes.iterator().next();
    assertEquals("123356536", snapshotProbe.getId());
    assertEquals("toString", snapshotProbe.getWhere().getMethodName());
    assertEquals(2, snapshotProbe.getWhere().getSourceLines().length);
    assertEquals("12-25", snapshotProbe.getWhere().getSourceLines()[0].toString());
    assertEquals("42-45", snapshotProbe.getWhere().getSourceLines()[1].toString());
    assertEquals(2, snapshotProbe.getTags().length);
    assertEquals("version:v123", snapshotProbe.getTags()[0].toString());
    assertEquals("env:staging", snapshotProbe.getTags()[1].toString());
    Configuration.FilterList allowList = configuration.getAllowList();
    assertNotNull(allowList);
    assertEquals(Arrays.asList("com.datadog", "org.apache"), allowList.getPackagePrefixes());
    Configuration.FilterList denyList = configuration.getDenyList();
    assertNotNull(denyList);
    assertEquals(Arrays.asList("java.security", "sun.security"), denyList.getPackagePrefixes());
  }

  private static void assertGoodConditionalProbeDefinition(Configuration configuration) {
    assertNotNull(configuration);
    assertGoodProbeDefinition(configuration);
    Collection<SnapshotProbe> snapshotProbes = configuration.getSnapshotProbes();
    SnapshotProbe snapshotProbe = snapshotProbes.iterator().next();
    assertNotNull(snapshotProbe.getProbeCondition());
  }

  private void assertGoodMetricProbeDefinition(Configuration configuration) {
    assertNotNull(configuration);
    Collection<MetricProbe> metricProbes = configuration.getMetricProbes();
    assertNotNull(metricProbes);
    assertEquals(3, metricProbes.size());
    Iterator<MetricProbe> it = metricProbes.iterator();
    MetricProbe metricProbe = it.next();
    assertEquals("123356536", metricProbe.getId());
    assertEquals("toString", metricProbe.getWhere().getMethodName());
    assertEquals("datadog.debugger.calls", metricProbe.getMetricName());
    assertEquals(MetricProbe.MetricKind.COUNT, metricProbe.getKind());
    assertEquals(new ValueScript(42.0), metricProbe.getValue());
    assertEquals(2, metricProbe.getTags().length);
    assertEquals("version:v123", metricProbe.getTags()[0].toString());
    assertEquals("env:staging", metricProbe.getTags()[1].toString());
    metricProbe = it.next();
    assertEquals("123356537", metricProbe.getId());
    assertEquals("toString", metricProbe.getWhere().getMethodName());
    assertEquals("datadog.debugger.gauge_value", metricProbe.getMetricName());
    assertEquals(MetricProbe.MetricKind.GAUGE, metricProbe.getKind());
    assertEquals(new ValueScript("^value"), metricProbe.getValue());
    assertEquals(2, metricProbe.getTags().length);
    assertEquals("version:v123", metricProbe.getTags()[0].toString());
    assertEquals("env:staging", metricProbe.getTags()[1].toString());
    Configuration.FilterList allowList = configuration.getAllowList();
    assertNotNull(allowList);
    assertEquals(Arrays.asList("com.datadog", "org.apache"), allowList.getPackagePrefixes());
    Configuration.FilterList denyList = configuration.getDenyList();
    assertNotNull(denyList);
    assertEquals(Arrays.asList("java.security", "sun.security"), denyList.getPackagePrefixes());
    metricProbe = it.next();
    assertEquals("123356538", metricProbe.getId());
    assertEquals("toString", metricProbe.getWhere().getMethodName());
    assertEquals("datadog.debugger.invalid_value", metricProbe.getMetricName());
    assertEquals(MetricProbe.MetricKind.GAUGE, metricProbe.getKind());
    assertEquals(new ValueScript("invalid"), metricProbe.getValue());
    assertEquals(2, metricProbe.getTags().length);
    assertEquals("version:v123", metricProbe.getTags()[0].toString());
    assertEquals("env:staging", metricProbe.getTags()[1].toString());
  }

  private static void assertGoodFleetProbeDefinition(Configuration configuration) {
    assertNotNull(configuration);
    Collection<SnapshotProbe> snapshotProbes = configuration.getSnapshotProbes();
    assertNotNull(snapshotProbes);
    assertEquals(4, snapshotProbes.size());
    Iterator<SnapshotProbe> it = snapshotProbes.iterator();
    SnapshotProbe probe1 = it.next();
    assertEquals("261874e7-3114-42fc-b2a4-6219926f3964", probe1.getId());
    assertEquals("showVetList", probe1.getWhere().getMethodName());
    assertEquals("VetController", probe1.getWhere().getTypeName());
    SnapshotProbe probe2 = it.next();
    assertEquals("8bd1ce9c-e8ea-4b0d-a11a-8a1d261d1a22", probe2.getId());
    assertEquals(
        "src/main/java/org/springframework/samples/petclinic/vet/VetController.java",
        probe2.getWhere().getSourceFile());
    assertEquals("119", probe2.getWhere().getSourceLines()[0].toString());
    SnapshotProbe probe3 = it.next();
    assertEquals("52229fd4-fad2-4405-b48e-56751b8e8a8e", probe3.getId());
    assertEquals("CrashController", probe3.getWhere().getTypeName());
    assertEquals("triggerException", probe3.getWhere().getMethodName());
    SnapshotProbe probe4 = it.next();
    assertEquals("1e79599c-6ea6-43ef-a12c-42cf6a0d6e7a", probe4.getId());
    assertEquals("CrashController", probe4.getWhere().getTypeName());
    assertEquals("caughtExceptions", probe4.getWhere().getMethodName());
  }

  static void verifyScheduleAtFixedRate(
      AgentTaskScheduler mockScheduler, long initialDelay, long period) {
    verify(mockScheduler)
        .scheduleAtFixedRate(any(), any(), eq(initialDelay), eq(period), eq(TimeUnit.MILLISECONDS));
  }

  static class MockScheduler {
    final AgentTaskScheduler mockScheduler;
    final AgentTaskScheduler.Scheduled mockScheduled;

    volatile long initialDelay;
    volatile long period;

    public MockScheduler() {
      mockScheduler = mock(AgentTaskScheduler.class);
      mockScheduled = mock(AgentTaskScheduler.Scheduled.class);
      when(mockScheduler.scheduleAtFixedRate(any(), any(), any(long.class), any(long.class), any()))
          .thenAnswer(this::answer);
    }

    private AgentTaskScheduler.Scheduled answer(InvocationOnMock invocationOnMock) {
      initialDelay = invocationOnMock.getArgument(2);
      period = invocationOnMock.getArgument(3);
      return mockScheduled;
    }

    public void verifyScheduleAtFixedRate(long initialDelay, long period) {
      Assertions.assertEquals(initialDelay, this.initialDelay);
      Assertions.assertEquals(period, this.period);
    }

    public AgentTaskScheduler getScheduler() {
      return mockScheduler;
    }
  }
}
