package datadog.smoketest;

import com.datadog.debugger.probe.LogProbe;
import datadog.trace.agent.test.utils.PortUtils;
import datadog.trace.util.TagsHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public class SpringBasedIntegrationTest extends BaseIntegrationTest {
  protected static final String DEBUGGER_TEST_APP_CLASS =
      "datadog.smoketest.debugger.SpringBootTestApplication";

  @Override
  protected String getAppClass() {
    return DEBUGGER_TEST_APP_CLASS;
  }

  @Override
  protected String getAppId() {
    return TagsHelper.sanitize("SpringBootTestApplication");
  }

  protected String startSpringApp(List<LogProbe> logProbes) throws Exception {
    return startSpringApp(logProbes, false);
  }

  protected String startSpringApp(List<LogProbe> logProbes, boolean enableProcessTags)
      throws Exception {
    setCurrentConfiguration(createConfig(logProbes));
    String httpPort = String.valueOf(PortUtils.randomOpenPort());
    ProcessBuilder processBuilder = createProcessBuilder(logFilePath, "--server.port=" + httpPort);
    if (!enableProcessTags) {
      processBuilder.environment().put("DD_EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED", "false");
    }
    targetProcess = processBuilder.start();
    // assert in logs app started
    waitForSpecificLogLine(
        logFilePath,
        "datadog.smoketest.debugger.SpringBootTestApplication - Started SpringBootTestApplication",
        Duration.ofMillis(100),
        Duration.ofSeconds(30));
    return httpPort;
  }

  protected void sendRequest(String httpPort, String urlPath) {
    OkHttpClient client = new OkHttpClient.Builder().build();
    Request request =
        new Request.Builder().url("http://localhost:" + httpPort + urlPath).get().build();
    try {
      client.newCall(request).execute();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  protected static void waitForSpecificLogLine(
      Path logFilePath, String line, Duration sleep, Duration timeout) throws IOException {
    boolean[] result = new boolean[] {false};
    long total = sleep.toNanos() == 0 ? 0 : timeout.toNanos() / sleep.toNanos();
    int i = 0;
    while (i < total && !result[0]) {
      Files.lines(logFilePath)
          .forEach(
              it -> {
                if (it.contains(line)) {
                  result[0] = true;
                }
              });
      LockSupport.parkNanos(sleep.toNanos());
      i++;
    }
  }
}
