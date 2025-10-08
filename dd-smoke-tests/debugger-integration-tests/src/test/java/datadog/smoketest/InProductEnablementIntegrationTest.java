package datadog.smoketest;

import com.datadog.debugger.probe.LogProbe;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class InProductEnablementIntegrationTest extends ServerAppDebuggerIntegrationTest {
  private List<String> additionalJvmArgs = new ArrayList<>();

  @Override
  protected ProcessBuilder createProcessBuilder(Path logFilePath, String... params) {
    List<String> commandParams = getDebuggerCommandParams();
    // remove the dynamic instrumentation flag
    commandParams.remove("-Ddd.dynamic.instrumentation.enabled=true");
    commandParams.addAll(additionalJvmArgs);
    return ProcessBuilderHelper.createProcessBuilder(
        commandParams, logFilePath, getAppClass(), params);
  }

  @Test
  @DisplayName("testDynamicInstrumentationEnablement")
  void testDynamicInstrumentationEnablement() throws Exception {
    appUrl = startAppAndAndGetUrl();
    setConfigOverrides(createConfigOverrides(true, false));
    LogProbe probe =
        LogProbe.builder().probeId(PROBE_ID).where(TEST_APP_CLASS_NAME, TRACED_METHOD_NAME).build();
    setCurrentConfiguration(createConfig(probe));
    waitForFeatureStarted(appUrl, "Dynamic Instrumentation");
    waitForInstrumentation(appUrl);
    // disable DI
    setConfigOverrides(createConfigOverrides(false, false));
    waitForFeatureStopped(appUrl, "Dynamic Instrumentation");
    waitForReTransformation(appUrl); // wait for retransformation of removed probe
  }

  @Test
  @DisplayName("testDynamicInstrumentationEnablementWithLineProbe")
  void testDynamicInstrumentationEnablementWithLineProbe() throws Exception {
    additionalJvmArgs.add("-Ddd.third.party.excludes=datadog.smoketest");
    appUrl = startAppAndAndGetUrl();
    setConfigOverrides(createConfigOverrides(true, false));
    LogProbe probe =
        LogProbe.builder()
            .probeId(LINE_PROBE_ID1)
            .where("ServerDebuggerTestApplication.java", 312)
            .build();
    setCurrentConfiguration(createConfig(probe));
    waitForFeatureStarted(appUrl, "Dynamic Instrumentation");
    execute(appUrl, "topLevelMethod", "");
    waitForInstrumentation(appUrl, "datadog.smoketest.debugger.TopLevel", true);
    // disable DI
    setConfigOverrides(createConfigOverrides(false, false));
    waitForFeatureStopped(appUrl, "Dynamic Instrumentation");
    waitForReTransformation(
        appUrl,
        "datadog.smoketest.debugger.TopLevel"); // wait for retransformation of removed probe
  }

  @Test
  @DisplayName("testDynamicInstrumentationEnablementStaticallyDisabled")
  void testDynamicInstrumentationEnablementStaticallyDisabled() throws Exception {
    // explicitly disable dynamic instrumentation, preventing enablement
    additionalJvmArgs.add("-Ddd.dynamic.instrumentation.enabled=false");
    appUrl = startAppAndAndGetUrl();
    setConfigOverrides(createConfigOverrides(true, false));
    LogProbe probe =
        LogProbe.builder().probeId(PROBE_ID).where(TEST_APP_CLASS_NAME, TRACED_METHOD_NAME).build();
    setCurrentConfiguration(createConfig(probe));
    waitForSpecificLine(appUrl, "Feature dynamic.instrumentation.enabled is explicitly disabled");
  }

  @Test
  @DisplayName("testExceptionReplayEnablement")
  void testExceptionReplayEnablement() throws Exception {
    additionalJvmArgs.add("-Ddd.third.party.excludes=datadog.smoketest");
    appUrl = startAppAndAndGetUrl();
    setConfigOverrides(createConfigOverrides(false, true));
    waitForFeatureStarted(appUrl, "Exception Replay");
    execute(appUrl, TRACED_METHOD_NAME, "oops"); // instrumenting first exception
    waitForInstrumentation(appUrl, SERVER_DEBUGGER_TEST_APP_CLASS, false);
    // disable ER
    setConfigOverrides(createConfigOverrides(false, false));
    waitForFeatureStopped(appUrl, "Exception Replay");
    waitForReTransformation(appUrl); // wait for retransformation of removed probes
  }

  private void waitForFeatureStarted(String appUrl, String feature) throws IOException {
    String line = "INFO com.datadog.debugger.agent.DebuggerAgent - Started " + feature;
    waitForSpecificLine(appUrl, line);
    LOG.info("feature {} started", feature);
  }

  private void waitForFeatureStopped(String appUrl, String feature) throws IOException {
    String line = "INFO com.datadog.debugger.agent.DebuggerAgent - Stopping " + feature;
    waitForSpecificLine(appUrl, line);
    LOG.info("feature {} stopped", feature);
  }

  private void waitForSpecificLine(String appUrl, String line) throws IOException {
    String url = String.format(appUrl + "/waitForSpecificLine?line=%s", line);
    sendRequest(url);
  }

  private static ConfigOverrides createConfigOverrides(
      boolean dynamicInstrumentationEnabled, boolean exceptionReplayEnabled) {
    ConfigOverrides config = new ConfigOverrides();
    config.libConfig = new LibConfig();
    config.libConfig.dynamicInstrumentationEnabled = dynamicInstrumentationEnabled;
    config.libConfig.exceptionReplayEnabled = exceptionReplayEnabled;
    return config;
  }
}
