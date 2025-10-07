package datadog.trace.bootstrap.benchmark;

import datadog.environment.SystemProperties;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StaticEventLogger {

  private static final Logger log = LoggerFactory.getLogger(StaticEventLogger.class);

  private static final int EVENT_BEGIN = '1';
  private static final int EVENT_END = '0';

  private static final BufferedWriter out;

  static {
    BufferedWriter writer = null;

    if ("true".equalsIgnoreCase(SystemProperties.get("dd.benchmark.enabled"))) {
      String dir = SystemProperties.get("dd.benchmark.output.dir");
      dir = (dir != null ? dir + File.separator : "");
      String fileName = dir + "startup_" + System.currentTimeMillis() + ".csv";

      try {
        writer = new BufferedWriter(new FileWriter(fileName), 32768);
        writer.write("Event,State,Timestamp");
        writer.newLine();
      } catch (IOException e) {
        log.warn("Can't start Benchmark event recording", e);
      }
    }

    out = writer;
  }

  public static void stop() {
    if (out != null) {
      try {
        // Add commit
        String commit = getAgentVersion();
        if (commit != null && !commit.isEmpty()) {
          out.write("# commit=");
          out.write(commit);
          out.newLine();
        }

        // Add current timestamp
        out.write("# time=");
        out.write(String.valueOf(System.currentTimeMillis()));
        out.newLine();

        out.close();
      } catch (IOException e) {
        log.warn("Can't close Benchmark event recording file", e);
      }
    }
  }

  private static void writeEvent(String event, int state, long timestamp) {
    try {
      out.write(event);
      out.write(',');
      out.write(state);
      out.write(',');
      out.write(String.valueOf(timestamp));
      out.newLine();
    } catch (IOException e) {
      log.warn("Can't write Benchmark event recording", e);
      stop();
    }
  }

  public static void begin(String event) {
    if (out == null) return;

    writeEvent(event, EVENT_BEGIN, System.nanoTime());
  }

  public static void end(String event) {
    if (out == null) return;

    writeEvent(event, EVENT_END, System.nanoTime());
  }

  private static String getAgentVersion() {
    final StringBuilder sb = new StringBuilder();
    try (final BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(
                    StaticEventLogger.class.getResourceAsStream("/dd-java-agent.version")),
                StandardCharsets.UTF_8))) {

      for (int c = reader.read(); c != -1; c = reader.read()) {
        sb.append((char) c);
      }
    } catch (Throwable ignored) {
      // swallow exception
      return null;
    }

    return sb.toString().trim();
  }
}
