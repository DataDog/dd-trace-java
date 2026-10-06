package datadog.trace.agent.test.coverage;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Self-contained offline view of the same evidence exported to JSON. */
final class HtmlReport {
  static void write(Path destination, String json) throws IOException {
    try (InputStream template = HtmlReport.class.getResourceAsStream("/method-observation.html")) {
      if (template == null) {
        throw new IOException("Missing context coverage HTML template");
      }
      String html = new String(template.readAllBytes(), UTF_8);
      // Encoding keeps arbitrary scenario names and stack text outside HTML/script syntax.
      String data = Base64.getEncoder().encodeToString(json.getBytes(UTF_8));
      Files.writeString(destination, html.replace("__COVERAGE_DATA__", data), UTF_8);
    }
  }

  private HtmlReport() {}
}
