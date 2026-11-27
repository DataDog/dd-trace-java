package datadog.smoketest.weblogic;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HealthCheck;
import com.github.dockerjava.api.model.LogConfig;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

class WebLogicT3SmokeTest {
  private static final long MEMORY = 2L * 1024 * 1024 * 1024;
  private static final String[] FIXTURE = {
    "Caller.java", "Echo.java", "EchoBean.java", "Lookup.java", "start.sh"
  };
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(strings = {"tracecontext", "datadog"})
  @Timeout(240)
  void propagatesWithoutCreatingTransportSpans(String style) throws Exception {
    DockerImageName image = DockerImageName.parse(System.getProperty("test.weblogic.image"));
    try (Network network =
            Network.builder().createNetworkCmdModifier(cmd -> cmd.withInternal(true)).build();
        GenericContainer<?> weblogic =
            new GenericContainer<>(image)
                .withNetwork(network)
                .withCommand("600")
                .withEnv("DD_TRACE_PROPAGATION_STYLE", style)
                .withTmpFs(Collections.singletonMap("/tmp", "rw,nosuid,nodev,size=384m,mode=1777"))
                .withCreateContainerCmdModifier(cmd -> {
                  cmd.withEntrypoint("/bin/sleep")
                      .withHealthcheck(
                          new HealthCheck().withTest(Collections.singletonList("NONE")));
                  cmd.getHostConfig()
                      .withReadonlyRootfs(true)
                      .withCapDrop(Capability.ALL)
                      .withSecurityOpts(Collections.singletonList("no-new-privileges"))
                      .withLogConfig(new LogConfig(LogConfig.LoggingType.NONE))
                      .withMemory(MEMORY)
                      .withMemorySwap(MEMORY);
                })) {
      weblogic.start();
      uploadFixture(weblogic);
      Container.ExecResult call =
          weblogic.execInContainer(
              "bash", "-c", "cd /tmp && jar xf fixture.jar && exec bash start.sh");
      Container.ExecResult firstLog = weblogic.execInContainer("cat", "/tmp/first.log");
      Container.ExecResult secondLog = weblogic.execInContainer("cat", "/tmp/second.log");
      Path reports =
          Paths.get(System.getProperty("datadog.smoketest.builddir"), "reports", "weblogic", style);
      Files.createDirectories(reports);
      Files.write(
          reports.resolve("caller.log"), (call.getStdout() + call.getStderr()).getBytes(UTF_8));
      Files.write(reports.resolve("first.log"), firstLog.getStdout().getBytes(UTF_8));
      Files.write(reports.resolve("second.log"), secondLog.getStdout().getBytes(UTF_8));
      assertEquals(0, call.getExitCode(), () -> call.getStderr() + "\nLogs: " + reports);
      assertTrue(call.getStdout().contains("T3 probe completed"), () -> "Logs: " + reports);
      assertEquals(0, firstLog.getExitCode(), firstLog.getStderr());
      assertEquals(0, secondLog.getExitCode(), secondLog.getStderr());

      List<JsonNode> caller = spans(call.getStdout());
      List<JsonNode> first = spans(firstLog.getStdout());
      List<JsonNode> second = spans(secondLog.getStdout());
      assertEquals(Arrays.asList("probe.caller", "probe.failure"), names(caller));
      assertEquals(Collections.singletonList("probe.nested"), names(first));
      assertEquals(Collections.singletonList("probe.read"), names(second));
      assertParent(caller.get(0), first.get(0));
      assertParent(first.get(0), second.get(0));
    }
  }

  private void uploadFixture(GenericContainer<?> container) throws Exception {
    Path archive = temp.resolve("fixture.jar");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
      zip.putNextEntry(new ZipEntry("agent.jar"));
      Files.copy(Paths.get(System.getProperty("datadog.smoketest.agent.shadowJar.path")), zip);
      zip.closeEntry();
      for (String name : FIXTURE) {
        try (InputStream source = getClass().getResourceAsStream("/weblogic/" + name)) {
          zip.putNextEntry(new ZipEntry(name));
          byte[] buffer = new byte[4096];
          int size;
          while ((size = source.read(buffer)) != -1) {
            zip.write(buffer, 0, size);
          }
          zip.closeEntry();
        }
      }
    }
    // Stream into the tmpfs because Docker's copy API requires a writable root filesystem.
    Path uploadLog = temp.resolve("upload.log");
    ProcessBuilder upload =
        new ProcessBuilder(
                "docker",
                "exec",
                "-i",
                container.getContainerId(),
                "sh",
                "-c",
                "cat > /tmp/fixture.jar")
            .redirectInput(archive.toFile())
            .redirectErrorStream(true)
            .redirectOutput(uploadLog.toFile());
    upload.environment().remove("DOCKER_CONTEXT");
    upload
        .environment()
        .put(
            "DOCKER_HOST",
            DockerClientFactory.instance().getTransportConfig().getDockerHost().toString());
    Process process = upload.start();
    try {
      assertTrue(process.waitFor(45, SECONDS), "Uploading fixture timed out");
      assertEquals(0, process.exitValue(), new String(Files.readAllBytes(uploadLog), UTF_8));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  private static List<JsonNode> spans(String output) throws Exception {
    List<JsonNode> spans = new ArrayList<>();
    for (String line : output.split("\\R")) {
      if (line.startsWith("{\"traces\"")) {
        for (JsonNode trace : JSON.readTree(line).required("traces")) {
          trace.forEach(spans::add);
        }
      }
    }
    return spans;
  }

  private static List<String> names(List<JsonNode> spans) {
    return spans.stream().map(span -> span.required("name").asText()).collect(Collectors.toList());
  }

  private static void assertParent(JsonNode parent, JsonNode child) {
    assertEquals(parent.required("trace_id"), child.required("trace_id"), "trace ID");
    assertEquals(parent.required("span_id"), child.required("parent_id"), "parent ID");
  }
}
