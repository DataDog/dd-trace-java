package com.datadog.profiling.uploader.util;

import com.datadog.profiling.controller.RecordingData;
import datadog.trace.api.IOLogger;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Call the `jfr` cli on the given recording */
public class JfrCliHelper {
  public static void invokeOn(final RecordingData data, final IOLogger ioLogger) {
    System.err.println("Hello World (1)");
    try {
      String javaHome = System.getProperty("java.home");
      if (javaHome == null || javaHome.isEmpty()) {
        ioLogger.error("Failed to gather information on recording, can't find JAVA_HOME");
        return;
      }

      Path jfr = Paths.get(javaHome, "bin", "jfr");
      if (!Files.exists(jfr)) {
        ioLogger.error("Failed to gather information on recording, can't find `jfr`");
        return;
      }

      // Create temporary file to save recording to
      File tmp = File.createTempFile("recording-", ".jfr");
      // Make sure files don't accumulate on the host
      tmp.deleteOnExit();

      // Save recording to temporary file
      InputStream in = data.getStream();
      try (FileOutputStream out = new FileOutputStream(tmp)) {
        redirect(in, out);
      }

      String[] stdout;

      // Launch `jfr` on temporary file and get stdout
      ProcessBuilder builder = new ProcessBuilder(jfr.toString(), "summary", tmp.getAbsolutePath());
      builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
      builder.redirectOutput(ProcessBuilder.Redirect.PIPE); // we'll want to read stdout
      builder.redirectError(ProcessBuilder.Redirect.INHERIT);

      Process process = builder.start();

      try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        redirect(process.getInputStream(), out);
        process.waitFor();

        stdout = out.toString().split(System.lineSeparator());
      }

      // Skip metadata from stdout
      int i = 0;
      for (; i < stdout.length; i++) {
        String line = stdout[i];
        if (line.matches("^=+$")) {
          // we reached the separation line between the metadata and the data
          i += 1;
          break;
        }
      }

      // Extract list of events from stdout
      List<Event> events = new ArrayList<Event>();
      for (; i < stdout.length; i++) {
        String line = stdout[i];
        if (line.isEmpty()) {
          break;
        }

        String[] columns = line.trim().split("\\s+", 3);
        if (columns.length < 3) {
          ioLogger.error("Failed to gather information on recording, line format is unexpected");
          return;
        }

        String type = columns[0];
        int count = Integer.parseInt(columns[1]);
        int size = Integer.parseInt(columns[2]);

        events.add(new Event(type, count, size));
      }

      System.err.println("Hello World (2)");

      // Log top 10 biggest events by size
      events.stream()
          .sorted(Comparator.comparing(Event::getSize).reversed())
          .limit(10)
          .forEach(
              event -> {
                  System.err.println("Hello World (3)");
                  ioLogger.error(
                      String.format(
                          "Event: %s, size = %d, count = %d",
                          event.getType(), event.getSize(), event.getCount()));
              });
    } catch (Exception e) {
      ioLogger.error("Failed to gather information on recording", e);
      return;
    }
  }

  private static final int BUFFER_SIZE = 8192;

  private static void redirect(InputStream in, OutputStream out) throws IOException {
    byte[] buffer = new byte[BUFFER_SIZE];
    int read;
    while ((read = in.read(buffer, 0, BUFFER_SIZE)) >= 0) {
      out.write(buffer, 0, read);
    }
  }

  private static class Event {
    private final String type;
    private final int count;
    private final int size;

    Event(String type, int count, int size) {
      this.type = type;
      this.count = count;
      this.size = size;
    }

    public String getType() {
      return type;
    }

    public int getCount() {
      return count;
    }

    public int getSize() {
      return size;
    }
  }
}
