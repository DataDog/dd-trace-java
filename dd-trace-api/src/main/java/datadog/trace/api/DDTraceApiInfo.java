package datadog.trace.api;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class DDTraceApiInfo {
  public static final String VERSION;

  static {
    String v;
    try (final BufferedReader br =
        new BufferedReader(
            new InputStreamReader(
                DDTraceApiInfo.class.getResourceAsStream("/dd-trace-api.version"), UTF_8))) {
      final StringBuilder sb = new StringBuilder();

      for (int c = br.read(); c != -1; c = br.read()) {
        sb.append((char) c);
      }

      v = sb.toString().trim();
    } catch (final Exception e) {
      v = "unknown";
    }
    VERSION = v;
  }
}
