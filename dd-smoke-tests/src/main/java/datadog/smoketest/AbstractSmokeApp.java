package datadog.smoketest;

import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.smoketest.backend.TraceBackend;
import datadog.smoketest.backend.Traces;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
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
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Base for a smoke-test application launched in its own JVM and managed as a JUnit 5 extension.
 * Declare a concrete {@link SmokeServerApp} or {@link SmokeCliApp} as a {@code static
 * &#64;RegisterExtension} field: {@link #beforeAll} launches the app (and its owned {@link
 * TraceBackend}) once per class, {@link #beforeEach} resets per method, and {@link #afterAll} tears
 * everything down — no {@code @TestInstance(PER_CLASS)} required (Q7). Access the handle through
 * the field or via {@link ParameterResolver} injection of the app / {@link Traces} / {@link
 * TraceBackend} into a test method (Q7).
 *
 * <p>This base owns everything common to both app shapes — process launch, log capture, the
 * no-error-logs and telemetry teardown checks, backend wiring, and the fluent {@link Builder}. The
 * two subclasses differ only in start-up readiness and per-method reset ({@link #onStarted()} /
 * {@link #onBeforeEach()}) and in their shape-specific API:
 *
 * <ul>
 *   <li>{@link SmokeServerApp} — a long-running HTTP server: adds {@code httpPort()}/{@code
 *       url()}/{@code get(...)}, waits for its port on start-up, and resets the backend between
 *       methods.
 *   <li>{@link SmokeCliApp} — a batch/CLI app that runs to completion: adds {@code
 *       assertCompletesWithValue(...)}.
 * </ul>
 *
 * <p>Core launch capabilities are reproduced here (Q6); several are deliberately deferred and
 * marked with explicit {@code // TODO}s so the gaps are discoverable rather than silent.
 */
public abstract class AbstractSmokeApp
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
  private final long startupTimeoutSeconds;
  private final Predicate<String> errorLogFilter;
  private final boolean checkErrorLogs;
  private final boolean checkTelemetry;

  private final OutputThreads outputThreads = new OutputThreads();
  private Process process;
  private File logFile;
  private boolean telemetryChecked;

  protected AbstractSmokeApp(Builder<?, ?> builder) {
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
    this.startupTimeoutSeconds = builder.startupTimeoutSeconds;
    this.checkErrorLogs = builder.checkErrorLogs;
    this.checkTelemetry = builder.checkTelemetry;
    this.errorLogFilter =
        builder.errorLogFilter != null
            ? builder.errorLogFilter
            : defaultErrorLogFilter(builder.allowedErrorLogs);
  }

  // --- Handle API (field access) ---

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

  // --- Shared state exposed to subclasses (start-up / per-method hooks) ---

  /** The app's (log/diagnostic) name. */
  protected final String name() {
    return this.name;
  }

  /** The launched process, or {@code null} before {@link #beforeAll}. */
  protected final Process process() {
    return this.process;
  }

  /** How long start-up may wait for the app to become ready. */
  protected final long startupTimeoutSeconds() {
    return this.startupTimeoutSeconds;
  }

  /** Whether this app owns its backend's lifecycle (i.e. the backend is not shared, S6). */
  protected final boolean ownsBackend() {
    return this.ownsBackend;
  }

  /**
   * Registers an additional launch-time placeholder (e.g. a subclass's {@code ${app.httpPort}}).
   */
  protected final void registerPlaceholder(String token, Supplier<String> value) {
    this.placeholders.put(token, value);
  }

  // --- Lifecycle (per-class start, per-method reset, teardown) ---

  @Override
  public final void beforeAll(ExtensionContext context) throws Exception {
    this.backend.start();
    launch();
    onStarted();
  }

  @Override
  public final void beforeEach(ExtensionContext context) {
    onBeforeEach();
    this.outputThreads.clearMessages();
  }

  @Override
  public final void afterEach(ExtensionContext context) {
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
  public final void afterAll(ExtensionContext context) {
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

  /**
   * Invoked once right after the app process is launched (in {@link #beforeAll}). Subclasses assert
   * their notion of "ready": a server waits for its port, a batch app fails fast on abnormal exit.
   */
  protected abstract void onStarted() throws Exception;

  /**
   * Invoked before each test method (in {@link #beforeEach}), before the captured log is reset. The
   * default is a no-op (a batch app may already have completed); a server asserts it's alive and
   * resets the backend.
   */
  protected void onBeforeEach() {}

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
    String result = value;
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
   * assert it with {@code backend().telemetry().waitForFlat(...)}.
   */
  public void assertTelemetryReceived() {
    this.backend.telemetry().waitForCount(1, this.startupTimeoutSeconds);
  }

  /**
   * The default error-log predicate: a line is an error if it contains {@code ERROR}, {@code
   * ASSERTION FAILED}, or {@code Failed to handle exception in instrumentation} — unless it
   * contains one of the {@code allowed} substrings (the allowlist escape hatch). Package-private
   * for testing.
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
    return type.isInstance(this) || type == Traces.class || type == TraceBackend.class;
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context) {
    Class<?> type = parameterContext.getParameter().getType();
    if (type.isInstance(this)) {
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

  /**
   * Fluent builder shared by the concrete apps. Self-typed ({@code B}) so every setter returns the
   * concrete builder for chaining; {@code build()} returns the concrete app ({@code A}). Obtain one
   * via {@link SmokeServerApp#named(String)} or {@link SmokeCliApp#named(String)}.
   */
  public abstract static class Builder<A extends AbstractSmokeApp, B extends Builder<A, B>> {
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
    private long startupTimeoutSeconds = 120;
    private Predicate<String> errorLogFilter;
    private final List<String> allowedErrorLogs = new ArrayList<>();
    private boolean checkErrorLogs = true;
    private boolean checkTelemetry = true;

    protected Builder(String name) {
      this.name = name;
    }

    /** Returns {@code this} as the concrete builder type, for fluent chaining. */
    protected abstract B self();

    /** Builds the concrete app. Implementations must call {@link #validate()} first. */
    public abstract A build();

    /** Runs {@code java -jar <jarPath>}. Mutually exclusive with {@link #mainClass(String)}. */
    public B jar(String jarPath) {
      this.jar = jarPath;
      return self();
    }

    /** Runs {@code java -cp <classpath> <mainClass>} (classpath defaults to the current one). */
    public B mainClass(String mainClass) {
      this.mainClass = mainClass;
      return self();
    }

    /** Classpath for {@link #mainClass(String)} mode; defaults to the launching JVM's classpath. */
    public B classpath(String classpath) {
      this.classpath = classpath;
      return self();
    }

    /**
     * Program arguments (after the jar/main class). Supports launch-time {@code ${...}}
     * placeholders (see {@link #placeholder(String, Supplier)}); {@link SmokeServerApp} also
     * provides {@code ${app.httpPort}}.
     */
    public B args(String... args) {
      this.programArgs.addAll(Arrays.asList(args));
      return self();
    }

    /**
     * Extra JVM arguments (before the jar/main class). Supports the same launch-time {@code ${...}}
     * placeholders as {@link #args(String...)}.
     */
    public B jvmArgs(String... jvmArgs) {
      this.jvmArgs.addAll(Arrays.asList(jvmArgs));
      return self();
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
     * .placeholder("rabbit.port", () -> String.valueOf(RABBIT.getMappedPort(5672)))
     * .args("--spring.rabbitmq.port=${rabbit.port}")
     * }</pre>
     */
    public B placeholder(String name, Supplier<String> value) {
      this.placeholders.put("${" + name + "}", value);
      return self();
    }

    /** Sets an environment variable for the launched process (applied after the defaults). */
    public B env(String key, String value) {
      this.extraEnv.put(key, value);
      return self();
    }

    /** Working directory for the launched process. */
    public B workingDirectory(File workingDirectory) {
      this.workingDirectory = workingDirectory;
      return self();
    }

    /**
     * The backend the app sends traces to; started/stopped by this app unless it is shared (S6).
     */
    public B backend(TraceBackend backend) {
      this.backend = backend;
      return self();
    }

    /**
     * Overrides the agent jar (default: the {@code datadog.smoketest.agent.shadowJar.path}
     * property).
     */
    public B javaAgent(String agentJarPath) {
      this.explicitAgentJar = agentJarPath;
      return self();
    }

    /** Launches the app without {@code -javaagent} (e.g. for launch-mechanics tests). */
    public B noAgent() {
      this.noAgent = true;
      return self();
    }

    /** How long start-up waits for the app to become ready (default 120s). */
    public B startupTimeoutSeconds(long seconds) {
      this.startupTimeoutSeconds = seconds;
      return self();
    }

    /**
     * Overrides how a captured log line is judged an error (default: contains {@code ERROR} /
     * {@code ASSERTION FAILED} / an instrumentation-exception marker). Replaces the allowlist.
     */
    public B errorLogFilter(Predicate<String> isError) {
      this.errorLogFilter = isError;
      return self();
    }

    /** Allowlists log lines containing any of these substrings from the default error-log check. */
    public B allowedErrorLogs(String... substrings) {
      this.allowedErrorLogs.addAll(Arrays.asList(substrings));
      return self();
    }

    /** Disables the automatic no-error-logs check at teardown (e.g. for error-case tests). */
    public B skipErrorLogCheck() {
      this.checkErrorLogs = false;
      return self();
    }

    /**
     * Disables the automatic app-started telemetry check at teardown (for agent apps that run with
     * telemetry disabled, e.g. {@code dd.instrumentation.telemetry.enabled=false}).
     */
    public B skipTelemetryCheck() {
      this.checkTelemetry = false;
      return self();
    }

    private String resolveAgentJar() {
      if (this.noAgent) {
        return null;
      }
      return this.explicitAgentJar != null
          ? this.explicitAgentJar
          : System.getProperty(AGENT_JAR_PROPERTY);
    }

    /** Validates common invariants; concrete {@link #build()} implementations must call this. */
    protected void validate() {
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
    }
  }
}
