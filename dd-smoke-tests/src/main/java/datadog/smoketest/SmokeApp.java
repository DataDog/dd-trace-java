package datadog.smoketest;

import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.smoketest.backend.TraceBackend;
import datadog.smoketest.backend.Traces;
import datadog.trace.agent.test.utils.PortUtils;
import datadog.trace.api.internal.VisibleForTesting;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * A smoke-test application launched in its own JVM, managed as a JUnit 5 extension. Declare it as a
 * {@code static @RegisterExtension} field: its {@link #beforeAll} launches the app (and its owned
 * {@link TraceBackend}) once per class, {@link #beforeEach} resets per method, and {@link
 * #afterAll} tears everything down — no {@code @TestInstance(PER_CLASS)} required (Q7). Access the
 * handle either through the field ({@code app.get(...)}, {@code app.traces()}) or via {@link
 * ParameterResolver} injection of {@link SmokeApp}/{@link Traces} into a test method (Q7).
 *
 * <pre>{@code
 * @RegisterExtension
 * static final SmokeApp app = SmokeApp.named("springboot")
 *     .jar(System.getProperty("datadog.smoketest.springboot.shadowJar.path"))
 *     .args("--server.port=${app.httpPort}")
 *     .backend(TraceBackend.mockAgent())
 *     .build();
 * }</pre>
 *
 * <p>Core launch capabilities are reproduced here (Q6); several are deliberately deferred and
 * marked with explicit {@code // TODO}s below so the gaps are discoverable rather than silent.
 */
public final class SmokeApp
    implements BeforeAllCallback,
        AfterAllCallback,
        BeforeEachCallback,
        AfterEachCallback,
        ParameterResolver {

  // Defaults mirroring the Groovy ProcessManager base so ported tests behave the same.
  private static final String SERVICE_NAME = "smoke-test-java-app";
  private static final String ENV = "smoketest";
  private static final String VERSION = "99";
  private static final String API_KEY = "01234567890abcdef123456789ABCDEF";
  private static final Set<String> NOISY_ENVIRONMENT_VARIABLES =
      new HashSet<>(Arrays.asList("CI_COMMIT_TITLE", "CI_COMMIT_MESSAGE", "CI_COMMIT_DESCRIPTION"));
  private static final String AGENT_JAR_PROPERTY = "datadog.smoketest.agent.shadowJar.path";
  private static final String BUILD_DIR_PROPERTY = "datadog.smoketest.builddir";
  private static final String HTTP_PORT_PLACEHOLDER = "${app.httpPort}";

  private final String name;
  private final String jar;
  private final String mainClass;
  private final String classpath;
  private final List<String> jvmArgs;
  private final List<String> programArgs;
  private final Map<String, Supplier<String>> placeholders;
  private final Map<String, String> extraEnv;
  private final File workingDirectory;
  private final TraceBackend backend;
  private final boolean ownsBackend;
  private final String agentJar; // null => launch without -javaagent
  private final boolean server; // wait for the HTTP port to open on start
  private final long startupTimeoutSeconds;
  private final int httpPort;
  private final Predicate<String> errorLogFilter;
  private final boolean checkErrorLogs;
  private final boolean checkTelemetry;

  private final OkHttpClient httpClient = new OkHttpClient();
  private final OutputThreads outputThreads = new OutputThreads();
  private Process process;
  private File logFile;
  private boolean telemetryChecked;

  private SmokeApp(Builder builder) {
    this.name = builder.name;
    this.jar = builder.jar;
    this.mainClass = builder.mainClass;
    this.classpath =
        builder.classpath != null ? builder.classpath : System.getProperty("java.class.path");
    this.jvmArgs = new ArrayList<>(builder.jvmArgs);
    this.programArgs = new ArrayList<>(builder.programArgs);
    this.placeholders = new LinkedHashMap<>(builder.placeholders);
    this.extraEnv = new HashMap<>(builder.extraEnv);
    this.workingDirectory = builder.workingDirectory;
    this.backend = builder.backend;
    this.ownsBackend = !builder.backend.isShared();
    this.agentJar = builder.resolveAgentJar();
    this.server = builder.server;
    this.startupTimeoutSeconds = builder.startupTimeoutSeconds;
    this.httpPort = PortUtils.randomOpenPort();
    this.checkErrorLogs = builder.checkErrorLogs;
    this.checkTelemetry = builder.checkTelemetry;
    this.errorLogFilter =
        builder.errorLogFilter != null
            ? builder.errorLogFilter
            : defaultErrorLogFilter(builder.allowedErrorLogs);
  }

  /** Starts a fluent builder for an app with the given (log/diagnostic) name. */
  public static Builder named(String name) {
    return new Builder(name);
  }

  // --- Handle API (field access) ---

  /**
   * The randomly-allocated port a server app should bind (substituted for {@value
   * #HTTP_PORT_PLACEHOLDER}).
   */
  public int httpPort() {
    return this.httpPort;
  }

  /** Base URL of the app's HTTP server. */
  public URI url() {
    return URI.create("http://localhost:" + this.httpPort);
  }

  /**
   * Issues a GET to the app and returns the HTTP status code (the response is drained and closed).
   */
  @VisibleForTesting
  int get(String path) {
    String full = url() + (path.startsWith("/") ? path : "/" + path);
    Request request = new Request.Builder().url(full).get().build();
    try (Response response = this.httpClient.newCall(request).execute()) {
      return response.code();
    } catch (IOException e) {
      throw new IllegalStateException("GET " + full + " failed", e);
    }
  }

  /** The trace query/assert facade of this app's backend. */
  public Traces traces() {
    return this.backend.traces();
  }

  /** The backend this app sends traces to. */
  public TraceBackend backend() {
    return this.backend;
  }

  /**
   * Waits (up to the log helper's timeout) for a captured stdout/stderr line matching {@code
   * predicate}; returns {@code false} on timeout. Lines are reset per test method.
   */
  public boolean awaitLogLine(Function<String, Boolean> predicate) {
    try {
      return this.outputThreads.processTestLogLines(predicate);
    } catch (TimeoutException e) {
      return false;
    }
  }

  /**
   * Asserts a non-server (batch) app runs to completion within the timeout and exits with {@code
   * expectedExitValue}. Fails with an {@link AssertionError} if it doesn't terminate in time or
   * exits with a different code. Pass a non-zero value for apps expected to fail (e.g. a tool the
   * agent aborts).
   */
  public void assertCompletesWithValue(long timeout, TimeUnit unit, int expectedExitValue) {
    boolean exited;
    try {
      exited = this.process.waitFor(timeout, unit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(
          "Interrupted while waiting for app '" + this.name + "' to complete", e);
    }
    if (!exited) {
      throw new AssertionError(
          "App '" + this.name + "' did not complete within " + timeout + " " + unit);
    }
    int actual = this.process.exitValue();
    if (actual != expectedExitValue) {
      throw new AssertionError(
          "App '" + this.name + "' exited with " + actual + " but expected " + expectedExitValue);
    }
  }

  // --- Lifecycle (per-class start, per-method reset, teardown) ---

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    this.backend.start();
    launch();
    if (this.server) {
      PortUtils.waitForPortToOpen(this.httpPort, this.startupTimeoutSeconds, SECONDS, this.process);
    } else if (!this.process.isAlive() && this.process.exitValue() != 0) {
      throw new IllegalStateException(
          "App '"
              + this.name
              + "' exited abnormally on start (exit "
              + this.process.exitValue()
              + ")");
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    // A server app must stay up across methods, and its per-test traces are produced during the
    // test body — so assert it's alive and reset the backend between methods. A batch app
    // (notAServer) may have already run to completion and produced its traces at start-up, so
    // neither applies: requiring it alive would spuriously fail, and clearing would wipe its
    // traces.
    if (this.server) {
      if (this.process == null || !this.process.isAlive()) {
        throw new IllegalStateException(
            "App '" + this.name + "' is not alive at the start of a test");
      }
      if (this.ownsBackend) {
        this.backend.clear();
      }
    }
    this.outputThreads.clearMessages();
  }

  @Override
  public void afterEach(ExtensionContext context) {
    // Check telemetry once, here, while the app and backend are still up — afterAll is too late
    // (the
    // app is killed, and the per-method session clear may have wiped a once-only app-started). Only
    // for agent-instrumented apps (a no-agent app emits none).
    if (this.checkTelemetry && this.agentJar != null && !this.telemetryChecked) {
      this.telemetryChecked = true;
      assertTelemetryReceived();
    }
  }

  @Override
  public void afterAll(ExtensionContext context) {
    try {
      stopProcess();
    } finally {
      // Join the output threads first so the log file is fully flushed before we scan it.
      this.outputThreads.close();
      try {
        if (this.ownsBackend) {
          this.backend.close();
        }
      } finally {
        if (this.checkErrorLogs) {
          assertNoErrorLogs();
        }
      }
    }
  }

  private void launch() throws IOException {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());

    if (this.agentJar != null) {
      command.add("-javaagent:" + this.agentJar);
      command.add("-Ddd.trace.agent.host=" + this.backend.url().getHost());
      command.add("-Ddd.trace.agent.port=" + this.backend.port());
      command.add("-Ddd.service.name=" + SERVICE_NAME);
      command.add("-Ddd.env=" + ENV);
      command.add("-Ddd.version=" + VERSION);
      String sessionToken = this.backend.sessionToken();
      if (sessionToken != null) {
        command.add("-Ddd.trace.agent.test.session.token=" + sessionToken);
      }
      if (this.checkTelemetry) {
        // Emit telemetry promptly so app-started is captured before a (long-running server) app is
        // killed at teardown — mirrors the Groovy base's telemetry tests.
        command.add("-Ddd.telemetry.heartbeat.interval=1");
      }
    }
    for (String jvmArg : this.jvmArgs) {
      command.add(substitute(jvmArg));
    }
    if (this.jar != null) {
      command.add("-jar");
      command.add(this.jar);
    } else {
      command.add("-cp");
      command.add(this.classpath);
      command.add(this.mainClass);
    }
    for (String programArg : this.programArgs) {
      command.add(substitute(programArg));
    }

    ProcessBuilder processBuilder = new ProcessBuilder(command);
    if (this.workingDirectory != null) {
      processBuilder.directory(this.workingDirectory);
    }
    Map<String, String> env = processBuilder.environment();
    env.put("JAVA_HOME", System.getProperty("java.home"));
    env.put("DD_API_KEY", API_KEY);
    env.keySet().removeAll(NOISY_ENVIRONMENT_VARIABLES);
    env.putAll(this.extraEnv);
    processBuilder.redirectErrorStream(true);

    this.logFile = resolveLogFile();
    this.process = processBuilder.start();
    this.outputThreads.captureOutput(this.process, this.logFile);
  }

  private void stopProcess() {
    if (this.process == null) {
      return;
    }
    if (!this.process.isAlive()) {
      return;
    }
    this.process.destroy();
    try {
      if (!this.process.waitFor(5, SECONDS)) {
        this.process.destroyForcibly();
        this.process.waitFor(10, SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      this.process.destroyForcibly();
    }
  }

  private String substitute(String value) {
    String result = value.replace(HTTP_PORT_PLACEHOLDER, Integer.toString(this.httpPort));
    for (Map.Entry<String, Supplier<String>> placeholder : this.placeholders.entrySet()) {
      String token = placeholder.getKey();
      if (result.contains(token)) {
        // Resolved now (at launch), not when registered — the value's source (e.g. a container's
        // mapped port) may not exist until test infrastructure has started.
        result = result.replace(token, placeholder.getValue().get());
      }
    }
    return result;
  }

  private File resolveLogFile() {
    String buildDir = System.getProperty(BUILD_DIR_PROPERTY);
    File dir =
        buildDir != null
            ? new File(buildDir, "reports")
            : new File(System.getProperty("java.io.tmpdir"));
    dir.mkdirs();
    // TODO Q6 (deferred): retry-safe timestamped log file names so retries don't clobber prior
    // logs.
    return new File(dir, "smoke-app." + this.name + ".log");
  }

  /**
   * Asserts the app logged no error lines, per the configured filter. Reads the whole captured log
   * (everything since launch), so it mirrors the Groovy base's universal no-error-logs check.
   * Auto-invoked at teardown unless {@link Builder#skipErrorLogCheck()} was set; may also be called
   * explicitly mid-run.
   */
  public void assertNoErrorLogs() {
    if (this.logFile == null) {
      return; // never launched / nothing captured
    }
    List<String> errors = new ArrayList<>();
    try (BufferedReader reader =
        Files.newBufferedReader(this.logFile.toPath(), StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (this.errorLogFilter.test(line)) {
          errors.add(line);
        }
      }
    } catch (NoSuchFileException e) {
      return; // no output file was produced
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read app log " + this.logFile, e);
    }
    if (!errors.isEmpty()) {
      StringBuilder message =
          new StringBuilder("App '")
              .append(this.name)
              .append("' logged ")
              .append(errors.size())
              .append(" error line(s):");
      for (String error : errors) {
        message.append("\n  ").append(error);
      }
      throw new AssertionError(message.toString());
    }
  }

  /**
   * Asserts the app's telemetry pipeline is active — at least one telemetry message reached the
   * backend. Auto-invoked once at the first {@link #afterEach} (while the app + backend are still
   * up) unless {@link Builder#skipTelemetryCheck()}. It intentionally asserts "telemetry is
   * flowing" rather than a specific event: the once-only {@code app-started} is fragile under the
   * per-method session clear, whereas heartbeats keep arriving; a test wanting a specific event can
   * assert it with {@link #traces() backend}.{@code telemetry().waitForFlat(...)}.
   */
  public void assertTelemetryReceived() {
    this.backend.telemetry().waitForCount(1, this.startupTimeoutSeconds);
  }

  /**
   * The default error-log predicate: a line is an error if it contains {@code ERROR}, {@code
   * ASSERTION FAILED}, or {@code Failed to handle exception in instrumentation} — unless it
   * contains one of the {@code allowed} substrings (the FIXME-allowlist escape hatch).
   * Package-private for testing.
   */
  static Predicate<String> defaultErrorLogFilter(List<String> allowed) {
    List<String> allowlist = new ArrayList<>(allowed);
    return line -> {
      for (String allowedSubstring : allowlist) {
        if (line.contains(allowedSubstring)) {
          return false;
        }
      }
      return line.contains("ERROR")
          || line.contains("ASSERTION FAILED")
          || line.contains("Failed to handle exception in instrumentation");
    };
  }

  private static String javaExecutable() {
    return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
  }

  // --- ParameterResolver (injection by type; qualifier for multi-instance is deferred) ---

  @Override
  public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext context) {
    Class<?> type = parameterContext.getParameter().getType();
    return type == SmokeApp.class || type == Traces.class || type == TraceBackend.class;
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context) {
    Class<?> type = parameterContext.getParameter().getType();
    if (type == SmokeApp.class) {
      return this;
    }
    if (type == Traces.class) {
      return traces();
    }
    if (type == TraceBackend.class) {
      return this.backend;
    }
    throw new ParameterResolutionException("Cannot resolve parameter of type " + type);
    // TODO Q7: with multiple same-type apps/backends injection is ambiguous — add a qualifier
    //  annotation (e.g. @App("producer")) when a multi-app test needs parameter injection (S6).
  }

  /** Fluent builder for a {@link SmokeApp}. */
  public static final class Builder {
    private final String name;
    private String jar;
    private String mainClass;
    private String classpath;
    private final List<String> jvmArgs = new ArrayList<>();
    private final List<String> programArgs = new ArrayList<>();
    private final Map<String, Supplier<String>> placeholders = new LinkedHashMap<>();
    private final Map<String, String> extraEnv = new HashMap<>();
    private File workingDirectory;
    private TraceBackend backend;
    private String explicitAgentJar;
    private boolean noAgent;
    private boolean server = true;
    private long startupTimeoutSeconds = 120;
    private Predicate<String> errorLogFilter;
    private final List<String> allowedErrorLogs = new ArrayList<>();
    private boolean checkErrorLogs = true;
    private boolean checkTelemetry = true;

    private Builder(String name) {
      this.name = name;
    }

    /** Runs {@code java -jar <jarPath>}. Mutually exclusive with {@link #mainClass(String)}. */
    public Builder jar(String jarPath) {
      this.jar = jarPath;
      return this;
    }

    /** Runs {@code java -cp <classpath> <mainClass>} (classpath defaults to the current one). */
    public Builder mainClass(String mainClass) {
      this.mainClass = mainClass;
      return this;
    }

    /** Classpath for {@link #mainClass(String)} mode; defaults to the launching JVM's classpath. */
    public Builder classpath(String classpath) {
      this.classpath = classpath;
      return this;
    }

    /**
     * Program arguments (after the jar/main class). Supports the {@value #HTTP_PORT_PLACEHOLDER}
     * placeholder.
     */
    public Builder args(String... args) {
      this.programArgs.addAll(Arrays.asList(args));
      return this;
    }

    /**
     * Extra JVM arguments (before the jar/main class). Supports the {@value #HTTP_PORT_PLACEHOLDER}
     * placeholder.
     */
    public Builder jvmArgs(String... jvmArgs) {
      this.jvmArgs.addAll(Arrays.asList(jvmArgs));
      return this;
    }

    /**
     * Registers a launch-time placeholder: occurrences of <code>${name}</code> in {@link
     * #jvmArgs(String...) jvmArgs} and {@link #args(String...) args} are replaced with {@code
     * value.get()} when the app <em>launches</em> (in {@code beforeAll}), not when the builder
     * runs. Use for values only known once test infrastructure has started — e.g. a Testcontainers
     * mapped port, which is unavailable when the {@code static @RegisterExtension} fields
     * initialize:
     *
     * <pre>{@code
     * .placeholder("rabbit.port", () -> String.valueOf(RABBIT.container().getMappedPort(5672)))
     * .args("--spring.rabbitmq.port=${rabbit.port}")
     * }</pre>
     */
    public Builder placeholder(String name, Supplier<String> value) {
      this.placeholders.put("${" + name + "}", value);
      return this;
    }

    /** Sets an environment variable for the launched process (applied after the defaults). */
    public Builder env(String key, String value) {
      this.extraEnv.put(key, value);
      return this;
    }

    /** Working directory for the launched process. */
    public Builder workingDirectory(File workingDirectory) {
      this.workingDirectory = workingDirectory;
      return this;
    }

    /**
     * The backend the app sends traces to; started/stopped by this app unless it is shared (S6).
     */
    public Builder backend(TraceBackend backend) {
      this.backend = backend;
      return this;
    }

    /**
     * Overrides the agent jar (default: the {@code datadog.smoketest.agent.shadowJar.path}
     * property).
     */
    public Builder javaAgent(String agentJarPath) {
      this.explicitAgentJar = agentJarPath;
      return this;
    }

    /** Launches the app without {@code -javaagent} (e.g. for launch-mechanics tests). */
    public Builder noAgent() {
      this.noAgent = true;
      return this;
    }

    /** Declares the app is not an HTTP server, so start-up won't wait for a port to open. */
    public Builder notAServer() {
      this.server = false;
      return this;
    }

    /** How long start-up waits for a server app's port to open (default 120s). */
    public Builder startupTimeoutSeconds(long seconds) {
      this.startupTimeoutSeconds = seconds;
      return this;
    }

    /**
     * Overrides how a captured log line is judged an error (default: contains {@code ERROR} /
     * {@code ASSERTION FAILED} / an instrumentation-exception marker). Replaces the allowlist.
     */
    public Builder errorLogFilter(Predicate<String> isError) {
      this.errorLogFilter = isError;
      return this;
    }

    /** Allowlists log lines containing any of these substrings from the default error-log check. */
    public Builder allowedErrorLogs(String... substrings) {
      this.allowedErrorLogs.addAll(Arrays.asList(substrings));
      return this;
    }

    /** Disables the automatic no-error-logs check at teardown (e.g. for error-case tests). */
    public Builder skipErrorLogCheck() {
      this.checkErrorLogs = false;
      return this;
    }

    /**
     * Disables the automatic app-started telemetry check at teardown (for agent apps that run with
     * telemetry disabled, e.g. {@code dd.instrumentation.telemetry.enabled=false}).
     */
    public Builder skipTelemetryCheck() {
      this.checkTelemetry = false;
      return this;
    }

    private String resolveAgentJar() {
      if (this.noAgent) {
        return null;
      }
      return this.explicitAgentJar != null
          ? this.explicitAgentJar
          : System.getProperty(AGENT_JAR_PROPERTY);
    }

    public SmokeApp build() {
      if (this.backend == null) {
        throw new IllegalStateException("A TraceBackend is required — call backend(...)");
      }
      if ((this.jar == null) == (this.mainClass == null)) {
        throw new IllegalStateException("Exactly one of jar(...) or mainClass(...) must be set");
      }
      // TODO Q6 (deferred, opt-in mixins / .jvmArgs(...) escape hatch — not baked into the base):
      //  profiling args; crash-tracking args (-XX:OnError=...dd_crash_uploader.sh); memory tuning
      //  (ForkedTestUtils); retry log-file timestamping. Add as explicit opt-ins when a ported test
      //  needs them.
      return new SmokeApp(this);
    }
  }
}
