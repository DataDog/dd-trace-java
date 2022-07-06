package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.condition.JRE.JAVA_11;
import static org.junit.jupiter.api.condition.JRE.JAVA_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.datadog.debugger.util.RemoteConfigHelper;
import datadog.trace.api.Config;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.URL;
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

  private static void setFieldInConfig(Config config, String fieldName, Object value) {
    try {
      Field field = config.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(config, value);
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
    System.setProperty("dd.debugger.config-file", probeDefinitionUrl.getFile());
    DebuggerAgent.run(inst);
    verify(inst, never()).addTransformer(any(), eq(true));
    System.clearProperty("dd.debugger.config-file");
  }

  @Test
  @EnabledOnJre({JAVA_8, JAVA_11})
  public void runEnabledWithDatadogAgent() throws InterruptedException, IOException {
    MockWebServer datadogAgentServer = new MockWebServer();
    HttpUrl datadogAgentUrl = datadogAgentServer.url(URL_PATH);
    setFieldInConfig(Config.get(), "debuggerEnabled", true);
    setFieldInConfig(Config.get(), "debuggerSnapshotUrl", datadogAgentUrl.toString());
    setFieldInConfig(Config.get(), "agentUrl", datadogAgentUrl.toString());
    setFieldInConfig(Config.get(), "agentHost", "localhost");
    setFieldInConfig(Config.get(), "agentPort", datadogAgentServer.getPort());
    setFieldInConfig(Config.get(), "debuggerMaxPayloadSize", 4096L);
    String infoContent =
        "{\"endpoints\": [\"v0.4/traces\", \"debugger/v1/input\", \"v0.7/config\"]}";
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
    DebuggerAgent.run(inst);
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
    DebuggerAgent.run(inst);
    verify(inst, never()).addTransformer(any(), eq(true));
  }
}
