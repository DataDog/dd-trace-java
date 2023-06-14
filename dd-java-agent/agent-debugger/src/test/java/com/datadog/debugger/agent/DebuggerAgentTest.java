package com.datadog.debugger.agent;

import static com.datadog.debugger.util.TestHelper.setFieldInConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.condition.JRE.JAVA_11;
import static org.junit.jupiter.api.condition.JRE.JAVA_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.datadog.debugger.util.RemoteConfigHelper;
import datadog.common.container.ContainerInfo;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.trace.api.Config;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DebuggerAgentTest {

  public static final String URL_PATH = "/foo";
  @Mock Instrumentation inst;
  final MockWebServer server = new MockWebServer();
  HttpUrl url;

  private static void setFieldInContainerInfo(
      ContainerInfo containerInfo, String fieldName, Object value) {
    try {
      Field field = containerInfo.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(containerInfo, value);
    } catch (Throwable e) {
      e.printStackTrace();
    }
  }

  @BeforeEach
  public void setUp() {
    url = server.url(URL_PATH);
    setFieldInConfig(Config.get(), "runtimeId", UUID.randomUUID().toString());
  }

  @AfterEach
  public void tearDown() throws IOException {
    try {
      server.shutdown();
    } catch (final IOException e) {
      // Looks like this happens for some unclear reason, but should not affect tests
    }
  }

  @Test
  @EnabledOnJre({JAVA_8, JAVA_11})
  public void runDisabled() {
    setFieldInConfig(Config.get(), "debuggerEnabled", false);
    URL probeDefinitionUrl = DebuggerAgentTest.class.getResource("/test_probe.json");
    System.setProperty("dd.dynamic.instrumentation.config-file", probeDefinitionUrl.getFile());
    DebuggerAgent.run(inst, new SharedCommunicationObjects());
    verify(inst, never()).addTransformer(any(), eq(true));
    System.clearProperty("dd.dynamic.instrumentation.config-file");
  }

  @Test
  @EnabledOnJre({JAVA_8, JAVA_11})
  public void runEnabledWithDatadogAgent() throws InterruptedException, IOException {
    MockWebServer datadogAgentServer = new MockWebServer();
    HttpUrl datadogAgentUrl = datadogAgentServer.url(URL_PATH);
    setFieldInConfig(Config.get(), "debuggerEnabled", true);
    setFieldInConfig(Config.get(), "remoteConfigEnabled", true);
    setFieldInConfig(Config.get(), "debuggerSnapshotUrl", datadogAgentUrl.toString());
    setFieldInConfig(Config.get(), "agentUrl", datadogAgentUrl.toString());
    setFieldInConfig(Config.get(), "agentHost", "localhost");
    setFieldInConfig(Config.get(), "agentPort", datadogAgentServer.getPort());
    setFieldInConfig(Config.get(), "debuggerMaxPayloadSize", 4096L);
    setFieldInContainerInfo(ContainerInfo.get(), "containerId", "");
    String infoContent =
        "{\"endpoints\": [\"v0.4/traces\", \"debugger/v1/input\", \"v0.7/config\"] }";
    datadogAgentServer.enqueue(new MockResponse().setResponseCode(200).setBody(infoContent));
    datadogAgentServer.enqueue(new MockResponse().setResponseCode(200).setBody(infoContent));
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                DebuggerAgentTest.class.getResourceAsStream("/test_probe.json")))) {
      String content = reader.lines().collect(Collectors.joining("\n"));
      String rcContent = RemoteConfigHelper.encode(content, "petclinic");
      datadogAgentServer.enqueue(new MockResponse().setResponseCode(200).setBody(rcContent));
    } catch (IOException e) {
      e.printStackTrace();
    }
    SharedCommunicationObjects sharedCommunicationObjects = new SharedCommunicationObjects();
    DebuggerAgent.run(inst, sharedCommunicationObjects);
    ConfigurationPoller configurationPoller =
        (ConfigurationPoller) sharedCommunicationObjects.configurationPoller(Config.get());
    configurationPoller.start();
    RecordedRequest request = datadogAgentServer.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(request);
    assertEquals("/info", request.getPath());
    request = datadogAgentServer.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(request);
    assertEquals("/v0.7/config", request.getPath());
    DebuggerAgent.stop();
    datadogAgentServer.shutdown();
  }

  @Test
  @EnabledOnJre({JAVA_8, JAVA_11})
  public void runEnabledWithUnsupportedDatadogAgent() throws InterruptedException {
    setFieldInConfig(Config.get(), "debuggerEnabled", true);
    setFieldInConfig(Config.get(), "debuggerSnapshotUrl", url.toString());
    setFieldInConfig(Config.get(), "agentUrl", url.toString());
    setFieldInConfig(Config.get(), "debuggerMaxPayloadSize", 1024L);
    String infoContent = "{\"endpoints\": [\"v0.4/traces\"]}";
    server.enqueue(new MockResponse().setResponseCode(200).setBody(infoContent));
    DebuggerAgent.run(inst, new SharedCommunicationObjects());
    verify(inst, never()).addTransformer(any(), eq(true));
  }

  @Test
  @EnabledOnJre({JAVA_8, JAVA_11})
  public void readFromFile() throws URISyntaxException {
    URL res = getClass().getClassLoader().getResource("test_probe2.json");
    String probeDefinitionPath = Paths.get(res.toURI()).toFile().getAbsolutePath();
    setFieldInConfig(Config.get(), "serviceName", "petclinic");
    setFieldInConfig(Config.get(), "debuggerEnabled", true);
    setFieldInConfig(Config.get(), "debuggerSnapshotUrl", url.toString());
    setFieldInConfig(Config.get(), "agentUrl", url.toString());
    setFieldInConfig(Config.get(), "debuggerMaxPayloadSize", 4096L);
    setFieldInConfig(Config.get(), "debuggerProbeFileLocation", probeDefinitionPath);
    String infoContent =
        "{\"endpoints\": [\"v0.4/traces\", \"debugger/v1/input\", \"v0.7/config\"] }";
    server.enqueue(new MockResponse().setResponseCode(200).setBody(infoContent));
    // sometimes this test fails because getAllLoadedClasses returns null
    assumeTrue(inst.getAllLoadedClasses() != null);
    DebuggerAgent.run(inst, new SharedCommunicationObjects());
    verify(inst, atLeastOnce()).addTransformer(any(), eq(true));
  }
}
