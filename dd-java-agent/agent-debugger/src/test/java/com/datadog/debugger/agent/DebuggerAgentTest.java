package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.condition.JRE.JAVA_11;
import static org.junit.jupiter.api.condition.JRE.JAVA_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static utils.TestHelper.setEnvVar;
import static utils.TestHelper.setFieldInConfig;

import com.datadog.debugger.util.RemoteConfigHelper;
import datadog.common.container.ContainerInfo;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.trace.api.Config;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
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
    setFieldInConfig(Config.get(), "dynamicInstrumentationEnabled", false);
    DebuggerAgent.run(inst, new SharedCommunicationObjects());
    verify(inst, never()).addTransformer(any(), eq(true));
  }

  @Test
  @EnabledOnJre({JAVA_8, JAVA_11})
  public void runEnabledWithDatadogAgent() throws InterruptedException, IOException {
    MockWebServer datadogAgentServer = new MockWebServer();
    HttpUrl datadogAgentUrl = datadogAgentServer.url(URL_PATH);
    setFieldInConfig(Config.get(), "dynamicInstrumentationEnabled", true);
    setFieldInConfig(Config.get(), "remoteConfigEnabled", true);
    setFieldInConfig(Config.get(), "dynamicInstrumentationSnapshotUrl", datadogAgentUrl.toString());
    setFieldInConfig(Config.get(), "agentUrl", datadogAgentUrl.toString());
    setFieldInConfig(Config.get(), "agentHost", "localhost");
    setFieldInConfig(Config.get(), "agentPort", datadogAgentServer.getPort());
    setFieldInConfig(Config.get(), "dynamicInstrumentationMaxPayloadSize", 4096L);
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
    RecordedRequest request;
    do {
      request = datadogAgentServer.takeRequest(5, TimeUnit.SECONDS);
      assertNotNull(request);
    } while ("/info".equals(request.getPath()));
    assertEquals("/v0.7/config", request.getPath());
    DebuggerAgent.stop();
    datadogAgentServer.shutdown();
  }

  @Test
  @EnabledOnJre({JAVA_8, JAVA_11})
  public void runEnabledWithUnsupportedDatadogAgent() throws InterruptedException {
    setFieldInConfig(Config.get(), "dynamicInstrumentationEnabled", true);
    setFieldInConfig(Config.get(), "dynamicInstrumentationSnapshotUrl", url.toString());
    setFieldInConfig(Config.get(), "agentUrl", url.toString());
    setFieldInConfig(Config.get(), "dynamicInstrumentationMaxPayloadSize", 1024L);
    String infoContent = "{\"endpoints\": [\"v0.4/traces\"]}";
    server.enqueue(new MockResponse().setResponseCode(200).setBody(infoContent));
    DebuggerAgent.run(inst, new SharedCommunicationObjects());
    verify(inst, never()).addTransformer(any(), eq(true));
  }

  @Test
  @EnabledOnJre({JAVA_8, JAVA_11})
  public void readFromFile() throws URISyntaxException {
    URL res = getClass().getClassLoader().getResource("test_probe_file.json");
    String probeDefinitionPath = Paths.get(res.toURI()).toFile().getAbsolutePath();
    setFieldInConfig(Config.get(), "serviceName", "petclinic");
    setFieldInConfig(Config.get(), "dynamicInstrumentationEnabled", true);
    setFieldInConfig(Config.get(), "dynamicInstrumentationSnapshotUrl", url.toString());
    setFieldInConfig(Config.get(), "agentUrl", url.toString());
    setFieldInConfig(Config.get(), "dynamicInstrumentationMaxPayloadSize", 4096L);
    setFieldInConfig(Config.get(), "dynamicInstrumentationProbeFileLocation", probeDefinitionPath);
    String infoContent =
        "{\"endpoints\": [\"v0.4/traces\", \"debugger/v1/input\", \"v0.7/config\"] }";
    server.enqueue(new MockResponse().setResponseCode(200).setBody(infoContent));
    // sometimes this test fails because getAllLoadedClasses returns null
    assumeTrue(inst.getAllLoadedClasses() != null);
    DebuggerAgent.run(inst, new SharedCommunicationObjects());
    verify(inst, atLeastOnce()).addTransformer(any(), eq(true));
  }

  @Test
  @EnabledOnJre({JAVA_8, JAVA_11})
  public void tags() {
    Config config = mock(Config.class);
    when(config.getEnv()).thenReturn("staging");
    when(config.getVersion()).thenReturn("42.0");
    when(config.getHostName()).thenReturn("MyHost");
    Map<String, String> globalTags = new HashMap<>();
    globalTags.put("globalTag1", "globalValue1");
    globalTags.put("globalTag2", "globalValue2");
    when(config.getGlobalTags()).thenReturn(globalTags);
    // set env vars now to be cached by GitInfoProvider
    GitInfoProvider.INSTANCE.invalidateCache();
    setEnvVar("DD_GIT_COMMIT_SHA", "sha1");
    setEnvVar("DD_GIT_REPOSITORY_URL", "http://github.com");
    String tags;
    try {
      tags = DebuggerAgent.getDefaultTagsMergedWithGlobalTags(config);
    } finally {
      setEnvVar("DD_GIT_COMMIT_SHA", null);
      setEnvVar("DD_GIT_REPOSITORY_URL", null);
      GitInfoProvider.INSTANCE.invalidateCache();
    }
    Map<String, String> resultTags = new HashMap<>();
    String[] splitTags = tags.split(",");
    for (String splitTag : splitTags) {
      int idx = splitTag.indexOf(':');
      resultTags.put(splitTag.substring(0, idx), splitTag.substring(idx + 1));
    }
    assertEquals("staging", resultTags.get(Tags.ENV));
    assertEquals("42.0", resultTags.get("version"));
    assertEquals("MyHost", resultTags.get("host_name"));
    assertEquals("globalValue1", resultTags.get("globalTag1"));
    assertEquals("globalValue2", resultTags.get("globalTag2"));
    assertEquals("sha1", resultTags.get(Tags.GIT_COMMIT_SHA));
    assertEquals("http://github.com", resultTags.get(Tags.GIT_REPOSITORY_URL));
  }
}
