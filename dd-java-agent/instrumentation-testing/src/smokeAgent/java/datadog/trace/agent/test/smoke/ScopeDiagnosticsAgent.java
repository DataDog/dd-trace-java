package datadog.trace.agent.test.smoke;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** File-controlled companion agent used only by smoke-test child processes. */
public final class ScopeDiagnosticsAgent {
  private static final AtomicLong REPORT_IDS = new AtomicLong();

  private ScopeDiagnosticsAgent() {}

  public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
    if (arguments == null || arguments.isEmpty()) {
      throw new IllegalArgumentException("Scope diagnostics require a control directory");
    }
    File directory = new File(arguments).getAbsoluteFile();
    try {
      Properties config = read(new File(directory, "config"));
      String mode = config.getProperty("mode");
      if (!"server".equals(mode) && !"cli".equals(mode)) {
        throw new IllegalArgumentException("Unknown scope diagnostics mode: " + mode);
      }
      boolean cli = "cli".equals(mode);
      Class<?> agent = Class.forName("datadog.trace.bootstrap.Agent", false, null);
      Field field = agent.getDeclaredField("AGENT_CLASSLOADER");
      field.setAccessible(true);
      ClassLoader loader = (ClassLoader) field.get(null);
      if (loader == null
          || !loader.getClass().getName().equals("datadog.trace.bootstrap.DatadogClassLoader")) {
        throw new IllegalStateException("The Datadog agent must precede the diagnostics companion");
      }
      Map<String, byte[]> payload = payload();
      Class<?> injector = loader.loadClass("datadog.instrument.classinject.ClassInjector");
      injector
          .getMethod("injectClasses", Map.class, ClassLoader.class)
          .invoke(null, payload, loader);
      Class<?> bridge =
          loader.loadClass("datadog.trace.agent.test.scopediag.SmokeDiagnosticsBridge");
      bridge
          .getMethod(
              "install", Instrumentation.class, Map.class, String.class, String.class, long.class)
          .invoke(
              null,
              instrumentation,
              payload,
              config.getProperty("disabledChecks", ""),
              config.getProperty("reason", ""),
              Long.parseLong(config.getProperty("quiescenceMillis", "2000")));
      Method start = bridge.getMethod("start");
      Method finish = bridge.getMethod("finish");
      if (cli) {
        start.invoke(null);
        Runtime.getRuntime()
            .addShutdownHook(
                new Thread(
                    () -> {
                      try {
                        publish(
                            new File(directory, "final"),
                            response(0, (Properties) finish.invoke(null)));
                      } catch (Throwable failure) {
                        publishFailure(directory, "final", 0, failure);
                      }
                    },
                    "scope-diagnostics-shutdown"));
      }
      Thread controller =
          new Thread(() -> control(directory, start, finish, cli), "scope-diagnostics-control");
      controller.setDaemon(true);
      controller.start();
      Properties ready = new Properties();
      ready.setProperty("protocol", "1");
      publish(new File(directory, "ready"), ready);
    } catch (Throwable failure) {
      publishFailure(directory, "error", 0, failure);
      throw new IllegalStateException(
          "Cannot install smoke-test scope diagnostics", unwrap(failure));
    }
  }

  private static void control(File directory, Method start, Method finish, boolean cli) {
    File commandFile = new File(directory, "command");
    long previous = Long.MIN_VALUE;
    while (!Thread.currentThread().isInterrupted()) {
      try {
        if (commandFile.exists()) {
          Properties command = read(commandFile);
          long seq = Long.parseLong(command.getProperty("seq"));
          if (seq != previous) {
            previous = seq;
            try {
              Properties result;
              String action = command.getProperty("action");
              if ("start".equals(action)) {
                if (cli) {
                  throw new IllegalStateException("CLI diagnostics record one process lifetime");
                }
                start.invoke(null);
                result = new Properties();
                result.setProperty("status", "ok");
                result.setProperty("detail", "Recording started");
                result.setProperty("timeline", "");
                result.setProperty("eventCount", "0");
              } else if ("finish".equals(action)) {
                result = (Properties) finish.invoke(null);
              } else {
                throw new IllegalArgumentException("Unknown diagnostic command: " + action);
              }
              publish(new File(directory, "response-" + seq), response(seq, result));
            } catch (Throwable failure) {
              publishFailure(directory, "response-" + seq, seq, failure);
            }
          }
        }
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } catch (Throwable failure) {
        publishFailure(directory, "error", previous, failure);
        return;
      }
    }
  }

  private static Map<String, byte[]> payload() throws Exception {
    File ownJar =
        new File(
            ScopeDiagnosticsAgent.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    Map<String, byte[]> classes = new LinkedHashMap<>();
    try (JarFile jar = new JarFile(ownJar)) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (!name.startsWith("payload/") || !name.endsWith(".classdata")) {
          continue;
        }
        try (InputStream input = jar.getInputStream(entry)) {
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          byte[] buffer = new byte[8192];
          int count;
          while ((count = input.read(buffer)) != -1) {
            bytes.write(buffer, 0, count);
          }
          classes.put(name.substring(8, name.length() - 10).replace('/', '.'), bytes.toByteArray());
        }
      }
    }
    if (classes.isEmpty()) {
      throw new IllegalStateException("Missing diagnostic payload");
    }
    return classes;
  }

  private static Properties read(File file) throws Exception {
    Properties properties = new Properties();
    try (InputStream input = new FileInputStream(file)) {
      properties.load(input);
    }
    return properties;
  }

  private static Properties response(long seq, Properties data) {
    Properties result = new Properties();
    result.putAll(data);
    result.setProperty("protocol", "1");
    result.setProperty("seq", Long.toString(seq));
    return result;
  }

  private static void publish(File target, Properties data) throws IOException {
    // File.createTempFile initializes SecureRandom, whose security configuration can use NIO.
    File temporary =
        new File(
            target.getParentFile(), ".scope-diagnostics-" + REPORT_IDS.incrementAndGet() + ".tmp");
    if (!temporary.createNewFile()) {
      throw new IOException("Cannot create scope diagnostic report: " + temporary);
    }
    try {
      // Properties.store formats a date header, which can initialize locale providers and NIO.
      try (Writer writer =
          new BufferedWriter(
              new OutputStreamWriter(new FileOutputStream(temporary), StandardCharsets.US_ASCII))) {
        // Protocol field names are fixed ASCII keys; only values need escaping.
        for (String key : data.stringPropertyNames()) {
          writer.write(key);
          writer.write('=');
          writePropertyValue(writer, data.getProperty(key));
          writer.write('\n');
        }
      }
      // Publish by same-directory rename; never fall back to copying an incomplete report.
      if (!temporary.renameTo(target)) {
        throw new IOException("Cannot publish scope diagnostic report: " + target);
      }
    } finally {
      if (temporary.exists() && !temporary.delete()) {
        temporary.deleteOnExit();
      }
    }
  }

  private static void writePropertyValue(Writer writer, String value) throws IOException {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' || c == ' ') {
        writer.write('\\');
        writer.write(c);
      } else if (c < 0x20 || c > 0x7e) {
        writer.write("\\u");
        for (int shift = 12; shift >= 0; shift -= 4) {
          writer.write(Character.forDigit((c >>> shift) & 0xf, 16));
        }
      } else {
        writer.write(c);
      }
    }
  }

  @SuppressForbidden // Last-resort stderr when writing the diagnostic report fails.
  private static void publishFailure(File directory, String file, long seq, Throwable failure) {
    Properties result = new Properties();
    result.setProperty("status", "error");
    result.setProperty("detail", unwrap(failure).toString());
    try {
      publish(new File(directory, file), response(seq, result));
    } catch (Exception writingFailure) {
      System.err.println("Cannot publish scope diagnostic failure: " + writingFailure);
      failure.printStackTrace(System.err);
    }
  }

  private static Throwable unwrap(Throwable failure) {
    while (failure instanceof InvocationTargetException && failure.getCause() != null) {
      failure = failure.getCause();
    }
    return failure;
  }
}
