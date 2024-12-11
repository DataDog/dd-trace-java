package template;


import static datadog.test.agent.assertions.AgentSpanMatcher.span;
import static datadog.test.agent.assertions.AgentTraceAssertions.IGNORE_ADDITIONAL_TRACES;
import static datadog.test.agent.assertions.AgentTraceAssertions.assertTraces;
import static datadog.test.agent.assertions.AgentTraceMatcher.trace;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import datadog.test.agent.TestAgentClient;
import datadog.test.agent.junit5.TestAgentExtension;
import datadog.trace.agent.test.utils.OkHttpUtils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.io.File;
import java.io.IOException;

@ExtendWith(TestAgentExtension.class)
public class SomeTests {


  TestAgentClient agent;


  protected String shadowJarPath = System.getProperty("datadog.smoketest.agent.shadowJar.path");
  protected String buildDirectory = System.getProperty("datadog.smoketest.builddir");
  protected String applicationJarPath = System.getProperty("datadog.smoketest.application.jar.path");
  protected String logLevel = "info";

  private Process process;
  private final OkHttpClient client = OkHttpUtils.client();

  private Process startProcess() throws IOException {
    String tmp = "/tmp";
    String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";

    String[] commands = new String[]{
        javaPath,
        "-javaagent:" + this.shadowJarPath,
        "-XX:ErrorFile=" + tmp + "/hs_err_pid%p.log",
        "-Ddd.trace.agent.port=" + this.agent.port(),
        "-Ddd.env=smoketest",
        "-Ddd.version=99",

        // Disable JDBC for flakiness
        "-Ddd.integration.jdbc.enabled=false",

//        "-Ddd.profiling.enabled=true",
//        "-Ddd.profiling.start-delay=${PROFILING_START_DELAY_SECONDS}",
//        "-Ddd.profiling.upload.period=${PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS}",
//        "-Ddd.profiling.url=${getProfilingUrl()}",
//        "-Ddd.profiling.ddprof.enabled=${isDdprofSafe()}",
//        "-Ddd.profiling.ddprof.alloc.enabled=${isDdprofSafe()}",
        "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=" + this.logLevel,
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=" + this.logLevel,
        "-Ddd.site=",
        "-jar",
        this.applicationJarPath,
    };
    ProcessBuilder builder = new ProcessBuilder();
    builder.command(commands);
    builder.directory(new File(this.buildDirectory));
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
    return builder.start();
  }

  private void stopProcess(Process process) {
    if (process == null) {
      throw new AssertionError("process not started");
    }
    if (!process.isAlive()) {
      throw new AssertionError("process died");
    }
    process.destroy();
    try {
      process.waitFor(10, SECONDS);
      int code = process.exitValue();
      if (code != 0 && code != 143) {
        throw new AssertionError("process exited with code " + code);
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      try {
        process.waitFor(3, SECONDS);
      } catch (InterruptedException ignored) {
      }
      int code = process.exitValue();
      if (code != 0 && code != 143) {
        throw new AssertionError("process exited with code " + code);
      }
    }
  }

  public void waitForServer() {
    for (int i = 0; i < 10; i++) {
      try {
        request("/");
        return;
      } catch (IOException ignored) {
        sleep(500);
      }
    }
    throw new AssertionError("failed to wait for server");
  }

  public void request(String path) throws IOException {
    Request request = new Request.Builder().url("http://localhost:8080"+path).build();
    try (Response response = this.client.newCall(request).execute()) {
      System.out.println("Response: "+response.body().string());
    }
  }


  @Test
  public void test() {
    boolean isWorking = true;
    assertTrue(isWorking);

    Process process = null;
    try {
      process = startProcess();
      waitForServer();
      request("/fruits");
    } catch (IOException e) {
      fail("Failed to start process", e);
    } finally {
      stopProcess(process);
    }

    assertTraces(this.agent,
        IGNORE_ADDITIONAL_TRACES,
//        AgentTraceAssertions.Options::ignoredAdditionalTraces,
        trace(
            span()
                .withServiceName("root-servlet"),
            span()
                .withServiceName("root-servlet"),
            span()
                .withServiceName("root-servlet")
        ),
        trace(
            span(),
            span(),
            span()
        ),
        trace(
            span()
        ),
        trace(
            span()
        ),
        trace(
            span()
        )
    );

//    List<AgentTrace> traces = this.agent.traces();
//    System.out.println(">>> " + traces);
//    assertEquals(emptyList(), traces, "Traces should be empty");
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }



}
