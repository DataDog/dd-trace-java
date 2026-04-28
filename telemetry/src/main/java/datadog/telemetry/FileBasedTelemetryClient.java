package datadog.telemetry;

import datadog.trace.util.PidHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link TelemetryClient} that writes telemetry payloads to JSON files instead of posting them
 * over HTTP. Used in Bazel's hermetic sandbox where network access is forbidden.
 *
 * <p>Each request produces a single JSON file named {@code telemetry-{seq:020d}-{pid}.json}. The
 * zero-padded sequence prefix preserves ordering for deterministic replay.
 */
public class FileBasedTelemetryClient extends TelemetryClient {

  private static final Logger log = LoggerFactory.getLogger(FileBasedTelemetryClient.class);

  private static final String DD_TELEMETRY_REQUEST_TYPE = "DD-Telemetry-Request-Type";
  private static final HttpUrl PLACEHOLDER_URL =
      HttpUrl.get("http://localhost/bazel-file-telemetry");

  private final Path outputDir;
  private final AtomicLong sequence = new AtomicLong(0);

  public FileBasedTelemetryClient(Path outputDir) {
    super(null, null, PLACEHOLDER_URL, null);
    this.outputDir = outputDir;
  }

  @Override
  public Result sendHttpRequest(Request.Builder httpRequestBuilder) {
    Request request = httpRequestBuilder.url(PLACEHOLDER_URL).build();
    String requestType = request.header(DD_TELEMETRY_REQUEST_TYPE);

    try {
      ensureOutputDir();
      byte[] bytes = readBody(request);
      writeFileAtomically(bytes);
      if (log.isDebugEnabled()) {
        log.debug(
            "[bazel mode] Wrote telemetry payload {} ({} bytes) to {}",
            requestType,
            bytes.length,
            outputDir);
      }
      return Result.SUCCESS;
    } catch (IOException e) {
      log.error(
          "[bazel mode] Failed to write telemetry payload {} to {}", requestType, outputDir, e);
      return Result.FAILURE;
    }
  }

  private void ensureOutputDir() throws IOException {
    if (!Files.exists(outputDir)) {
      Files.createDirectories(outputDir);
    }
  }

  private static byte[] readBody(Request request) throws IOException {
    if (request.body() == null) {
      return new byte[0];
    }
    Buffer buffer = new Buffer();
    request.body().writeTo(buffer);
    return buffer.readByteArray();
  }

  private void writeFileAtomically(byte[] data) throws IOException {
    long seq = sequence.getAndIncrement();
    String pid = PidHelper.getPid();
    String filename = String.format("telemetry-%020d-%s.json", seq, pid);
    Path target = outputDir.resolve(filename);
    Path tmp = outputDir.resolve(filename + ".tmp");

    Files.write(tmp, data);
    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
  }
}
