package datadog.telemetry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.PidHelper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBasedTelemetryClientTest {

  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

  @Test
  void writesRequestBodyToFile(@TempDir Path tmp) throws IOException {
    Path outputDir = tmp.resolve("payloads/telemetry");
    FileBasedTelemetryClient client = new FileBasedTelemetryClient(outputDir);
    byte[] body = "{\"request_type\":\"app-started\"}".getBytes(StandardCharsets.UTF_8);

    TelemetryClient.Result result = client.sendHttpRequest(requestBuilder("app-started", body));

    assertEquals(TelemetryClient.Result.SUCCESS, result);
    List<Path> files = listFiles(outputDir);
    assertEquals(1, files.size());
    assertArrayEquals(body, Files.readAllBytes(files.get(0)));
  }

  @Test
  void createsOutputDirectoryIfMissing(@TempDir Path tmp) {
    Path outputDir = tmp.resolve("does/not/exist/yet");
    assertFalse(Files.exists(outputDir));

    FileBasedTelemetryClient client = new FileBasedTelemetryClient(outputDir);
    TelemetryClient.Result result =
        client.sendHttpRequest(
            requestBuilder("app-heartbeat", "{}".getBytes(StandardCharsets.UTF_8)));

    assertEquals(TelemetryClient.Result.SUCCESS, result);
    assertTrue(Files.isDirectory(outputDir));
  }

  @Test
  void filenameFollowsTelemetryConvention(@TempDir Path tmp) throws IOException {
    FileBasedTelemetryClient client = new FileBasedTelemetryClient(tmp);

    client.sendHttpRequest(requestBuilder("app-started", "{}".getBytes(StandardCharsets.UTF_8)));

    List<Path> files = listFiles(tmp);
    assertEquals(1, files.size());
    String expected = String.format("telemetry-%020d-%s.json", 0L, PidHelper.getPid());
    assertEquals(expected, files.get(0).getFileName().toString());
  }

  @Test
  void sequenceIncrementsPerRequest(@TempDir Path tmp) throws IOException {
    FileBasedTelemetryClient client = new FileBasedTelemetryClient(tmp);

    for (int i = 0; i < 3; i++) {
      client.sendHttpRequest(
          requestBuilder("app-heartbeat", "{}".getBytes(StandardCharsets.UTF_8)));
    }

    List<Path> files = listFiles(tmp);
    assertEquals(3, files.size());
    String pid = PidHelper.getPid();
    for (int i = 0; i < files.size(); i++) {
      // files are unsorted from DirectoryStream — build expected set, check contains
      String expected = String.format("telemetry-%020d-%s.json", (long) i, pid);
      assertTrue(
          files.stream().anyMatch(p -> p.getFileName().toString().equals(expected)),
          "Missing file with sequence " + i + ": " + expected);
    }
  }

  @Test
  void handlesNullRequestBody(@TempDir Path tmp) throws IOException {
    FileBasedTelemetryClient client = new FileBasedTelemetryClient(tmp);
    Request.Builder builder =
        new Request.Builder().addHeader("DD-Telemetry-Request-Type", "app-closing").get();

    TelemetryClient.Result result = client.sendHttpRequest(builder);

    assertEquals(TelemetryClient.Result.SUCCESS, result);
    List<Path> files = listFiles(tmp);
    assertEquals(1, files.size());
    assertEquals(0, Files.size(files.get(0)));
  }

  @Test
  void returnsFailureWhenOutputDirIsNotWritable(@TempDir Path tmp) throws IOException {
    // Create a regular file at the expected output-dir path so createDirectories fails.
    Path collision = tmp.resolve("not-a-dir");
    Files.createFile(collision);
    FileBasedTelemetryClient client = new FileBasedTelemetryClient(collision.resolve("sub"));

    TelemetryClient.Result result =
        client.sendHttpRequest(
            requestBuilder("app-heartbeat", "{}".getBytes(StandardCharsets.UTF_8)));

    assertEquals(TelemetryClient.Result.FAILURE, result);
  }

  @Test
  void leavesNoLingeringTempFiles(@TempDir Path tmp) throws IOException {
    FileBasedTelemetryClient client = new FileBasedTelemetryClient(tmp);

    client.sendHttpRequest(requestBuilder("app-started", "{}".getBytes(StandardCharsets.UTF_8)));

    List<Path> files = listFiles(tmp);
    assertFalse(
        files.stream().anyMatch(p -> p.getFileName().toString().endsWith(".tmp")),
        "Found leftover .tmp file: " + files);
  }

  private static Request.Builder requestBuilder(String requestType, byte[] body) {
    return new Request.Builder()
        .addHeader("DD-Telemetry-Request-Type", requestType)
        .post(RequestBody.create(JSON, body));
  }

  private static List<Path> listFiles(Path dir) throws IOException {
    List<Path> files = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path p : stream) {
        files.add(p);
      }
    }
    return files;
  }
}
