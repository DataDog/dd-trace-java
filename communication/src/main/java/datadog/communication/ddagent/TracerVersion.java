package datadog.communication.ddagent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class TracerVersion {
  public static final String TRACER_VERSION = getTracerVersion();

  private static String getTracerVersion() {
    ClassLoader cl = ClassLoader.getSystemClassLoader();
    try (final BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                cl.getResourceAsStream("dd-java-agent.version"), StandardCharsets.ISO_8859_1))) {
      String line = reader.readLine();
      return line != null ? line : "0.0.0";
    } catch (Exception e) {
      return "0.0.0";
    }
  }
}
