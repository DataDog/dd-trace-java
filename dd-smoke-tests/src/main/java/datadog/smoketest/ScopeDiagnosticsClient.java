package datadog.smoketest;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Controls scope recording in one smoke application's companion agent. */
public final class ScopeDiagnosticsClient {
  private static final String AGENT_PROPERTY = "datadog.smoketest.scopeDiagnostics.agent.path";
  private final Path directory;
  private final Path agent;
  private final boolean cli;
  private final long timeoutMillis;
  private long sequence;
  private boolean active;
  private boolean completed;

  public ScopeDiagnosticsClient(Path reportsDirectory, String name, boolean cli)
      throws IOException {
    this(reportsDirectory, name, cli, configuredAgent(), 30_000);
  }

  ScopeDiagnosticsClient(
      Path reportsDirectory, String name, boolean cli, Path agent, long timeoutMillis)
      throws IOException {
    if (!Files.isRegularFile(agent)) {
      throw new IllegalArgumentException("Scope diagnostics agent jar does not exist: " + agent);
    }
    this.agent = agent.toAbsolutePath();
    this.cli = cli;
    this.timeoutMillis = timeoutMillis;
    Files.createDirectories(reportsDirectory);
    this.directory =
        Files.createTempDirectory(
                reportsDirectory,
                "scope-diagnostics-" + name.replaceAll("[^a-zA-Z0-9._-]", "_") + "-")
            .toAbsolutePath();
    Properties config = new Properties();
    config.setProperty("mode", cli ? "cli" : "server");
    write("config", config);
  }

  public Path directory() {
    return directory;
  }

  public String javaAgentArgument() {
    return "-javaagent:" + agent + "=" + directory;
  }

  public void awaitReady(Process process) {
    validateProtocol(await("ready", process));
  }

  public void start(Process process) {
    if (cli) {
      return;
    }
    if (active) {
      throw failure("A recording window is already active");
    }
    request("start", process);
    active = true;
  }

  public void finish(Process process) {
    if (cli) {
      if (completed) {
        return;
      }
      if (process.isAlive()) {
        request("finish", process);
        completed = true;
      } else {
        verifyCompleted(process);
      }
    } else if (active) {
      active = false;
      request("finish", process);
    }
  }

  /** Checks CLI shutdown output, or completes a server window before process destruction. */
  public void verifyCompleted(Process process) {
    if (completed) {
      return;
    }
    if (!cli) {
      finish(process);
      return;
    }
    if (process.isAlive()) {
      finish(process);
      return;
    }
    validateResponse(await("final", process), 0);
    completed = true;
  }

  private void request(String action, Process process) {
    long id = ++sequence;
    Properties command = new Properties();
    command.setProperty("seq", Long.toString(id));
    command.setProperty("action", action);
    try {
      write("command", command);
    } catch (IOException e) {
      throw failure("Cannot send " + action + ": " + e);
    }
    Properties response = await("response-" + id, process, cli && "finish".equals(action));
    if (response == null) {
      // A CLI can exit before accepting finish; its shutdown report completes the same recording.
      validateResponse(await("final", process), 0);
    } else {
      validateResponse(response, id);
    }
  }

  private Properties await(String file, Process process) {
    return await(file, process, false);
  }

  /** Returns null only when the process exited without publishing the optional response. */
  private Properties await(String file, Process process, boolean allowMissingOnExit) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    for (; ; ) {
      try {
        if (Files.exists(directory.resolve("error"))) {
          throw failure("Companion agent failed: " + read("error"));
        }
        if (Files.exists(directory.resolve(file))) {
          return read(file);
        }
        if (!process.isAlive()) {
          // Shutdown can publish the report between our first file check and the exit check.
          if (Files.exists(directory.resolve("error"))) {
            throw failure("Companion agent failed: " + read("error"));
          }
          if (Files.exists(directory.resolve(file))) {
            return read(file);
          }
          if (allowMissingOnExit) {
            return null;
          }
          throw failure("Application exited without diagnostic " + file);
        }
        if (System.nanoTime() >= deadline) {
          throw failure("Timed out waiting for diagnostic " + file);
        }
        Thread.sleep(10);
      } catch (IOException e) {
        throw failure("Cannot read diagnostic " + file + ": " + e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw failure("Interrupted waiting for diagnostic " + file);
      }
    }
  }

  private void validateResponse(Properties response, long id) {
    validateProtocol(response);
    if (!Long.toString(id).equals(response.getProperty("seq"))) {
      throw failure("Diagnostic response has missing or incorrect sequence: " + response);
    }
    String status = response.getProperty("status");
    if (!"ok".equals(status) && !"problems".equals(status) && !"error".equals(status)) {
      throw failure("Diagnostic response has invalid status: " + response);
    }
    if (!"error".equals(status)) {
      try {
        if (Long.parseLong(response.getProperty("eventCount")) < 0
            || response.getProperty("detail") == null
            || response.getProperty("timeline") == null) {
          throw new IllegalArgumentException();
        }
      } catch (IllegalArgumentException e) {
        throw failure("Diagnostic response is incomplete: " + response);
      }
    }
    if (!"ok".equals(status)) {
      throw failure(
          response.getProperty("detail", status) + "\n" + response.getProperty("timeline", ""));
    }
  }

  private void validateProtocol(Properties properties) {
    if (!"1".equals(properties.getProperty("protocol"))) {
      throw failure("Missing or unsupported diagnostic protocol: " + properties);
    }
  }

  private Properties read(String name) throws IOException {
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(directory.resolve(name))) {
      properties.load(input);
    }
    return properties;
  }

  private void write(String name, Properties properties) throws IOException {
    Path temporary = Files.createTempFile(directory, name, ".tmp");
    try {
      try (OutputStream output = Files.newOutputStream(temporary)) {
        properties.store(output, null);
      }
      Files.move(temporary, directory.resolve(name), ATOMIC_MOVE, REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private AssertionError failure(String message) {
    return new AssertionError(message + "\nScope diagnostics: " + directory);
  }

  private static Path configuredAgent() {
    String agent = System.getProperty(AGENT_PROPERTY);
    if (agent == null || agent.trim().isEmpty()) {
      throw new IllegalStateException("Missing companion agent property: " + AGENT_PROPERTY);
    }
    return Paths.get(agent);
  }
}
