package datadog.trace.agent.test.smoke;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** File-controlled companion agent used only by smoke-test child processes. */
public final class ScopeDiagnosticsAgent {
  private ScopeDiagnosticsAgent() {}

  public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
    if (arguments == null || arguments.isEmpty()) {
      throw new IllegalArgumentException("Scope diagnostics require a control directory");
    }
    Path directory = Paths.get(arguments).toAbsolutePath();
    try {
      Properties config = read(directory.resolve("config"));
      String mode = config.getProperty("mode");
      if (!"server".equals(mode) && !"cli".equals(mode)) {
        throw new IllegalArgumentException("Unknown scope diagnostics mode: " + mode);
      }
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
          .getMethod("install", Instrumentation.class, Map.class)
          .invoke(null, instrumentation, payload);
      Method start = bridge.getMethod("start");
      Method finish = bridge.getMethod("finish");
      if ("cli".equals(mode)) {
        start.invoke(null);
        Runtime.getRuntime()
            .addShutdownHook(
                new Thread(
                    () -> {
                      try {
                        publish(
                            directory.resolve("final"),
                            response(0, (Properties) finish.invoke(null)));
                      } catch (Throwable failure) {
                        publishFailure(directory, "final", 0, failure);
                      }
                    },
                    "scope-diagnostics-shutdown"));
      }
      Thread controller =
          new Thread(
              () -> control(directory, start, finish, "cli".equals(mode)),
              "scope-diagnostics-control");
      controller.setDaemon(true);
      controller.start();
      Properties ready = new Properties();
      ready.setProperty("protocol", "1");
      publish(directory.resolve("ready"), ready);
    } catch (Throwable failure) {
      publishFailure(directory, "error", 0, failure);
      throw new IllegalStateException(
          "Cannot install smoke-test scope diagnostics", unwrap(failure));
    }
  }

  private static void control(Path directory, Method start, Method finish, boolean cli) {
    long previous = Long.MIN_VALUE;
    while (!Thread.currentThread().isInterrupted()) {
      try {
        Path commandFile = directory.resolve("command");
        if (Files.exists(commandFile)) {
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
              publish(directory.resolve("response-" + seq), response(seq, result));
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
    Path ownJar =
        Paths.get(
            ScopeDiagnosticsAgent.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    Map<String, byte[]> classes = new LinkedHashMap<>();
    try (JarFile jar = new JarFile(ownJar.toFile())) {
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

  private static Properties read(Path path) throws Exception {
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
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

  private static void publish(Path target, Properties data) throws Exception {
    Path temporary = Files.createTempFile(target.getParent(), ".scope-diagnostics-", ".tmp");
    try {
      try (OutputStream output = Files.newOutputStream(temporary)) {
        data.store(output, "Smoke-test scope diagnostics");
      }
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  @SuppressForbidden // Last-resort stderr when writing the diagnostic report fails.
  private static void publishFailure(Path directory, String file, long seq, Throwable failure) {
    Properties result = new Properties();
    result.setProperty("status", "error");
    result.setProperty("detail", unwrap(failure).toString());
    try {
      publish(directory.resolve(file), response(seq, result));
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
