package datadog.smoketest;

import static datadog.trace.test.util.ForkedTestUtils.getMaxMemoryArgumentForFork;
import static datadog.trace.test.util.ForkedTestUtils.getMinMemoryArgumentForFork;
import static java.time.ZoneOffset.UTC;
import static java.util.Arrays.asList;
import static java.util.Locale.ROOT;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;

import datadog.environment.OperatingSystem;
import datadog.smoketest.backend.AgentBackend;
import datadog.smoketest.backend.Traces;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.test.util.ForkedTestUtils;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * This class is a base class for a smoke-test application launched in its own JVM and managed as a
 * JUnit 5 extension. Declare a concrete {@link SmokeServerApp} or {@link SmokeCliApp} as a {@code
 * static @RegisterExtension} field. The extension mechanism will take care of the application
 * lifecycle:
 *
 * <ul>
 *   <li>{@link #beforeAll} launches the app (and its owned {@link AgentBackend}) and verify it is
 *       ready,
 *   <li>{@link #beforeEach} resets the output logs and the test agent session,
 *   <li>{@link #afterEach} checks the app health, then telemetry reception (only once, and owned
 *       backend),
 *   <li>{@link #afterAll} tears everything down and checks there was no error in logs.
 * </ul>
 *
 * The current implementations provide two kind of smoke applications:
 *
 * <ul>
 *   <li>{@link SmokeServerApp}: a long-running HTTP server: adds {@code httpPort()}/{@code
 *       url()}/{@code get(...)}, waits for its port on start-up, and resets the backend between
 *       methods.
 *   <li>{@link SmokeCliApp}: a batch/CLI app that runs to completion: adds {@code
 *       assertCompletesWithValue(...)}.
 * </ul>
 */
public abstract class AbstractSmokeApp
    implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

  // Defaults mirroring the Groovy ProcessManager base so ported tests behave the same.
  private static final String SERVICE_NAME = "smoke-test-java-app";
  private static final String ENV = "smoketest";
  private static final String VERSION = "99";
  private static final String API_KEY = "01234567890abcdef123456789ABCDEF";
  private static final long DEFAULT_STARTUP_TIMEOUT_SECONDS = 240;
  private static final String DEFAULT_LOG_LEVEL = "info";
  private static final String DEBUG_LOG_LEVEL = "debug";
  private static final Set<String> NOISY_ENVIRONMENT_VARIABLES =
      new HashSet<>(asList("CI_COMMIT_TITLE", "CI_COMMIT_MESSAGE", "CI_COMMIT_DESCRIPTION"));
  private static final String AGENT_JAR_PROPERTY = "datadog.smoketest.agent.shadowJar.path";
  private static final String BUILD_DIR_PROPERTY = "datadog.smoketest.builddir";
  private static final DateTimeFormatter LOG_FILE_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss.SSS", ROOT).withZone(UTC);

  // Repository-wide known-flaky log lines excluded from the default error-log check.
  private static final List<String> DEFAULT_ERROR_LOG_EXCLUSIONS =
      asList(
          // FIXME: Flaky profiler exception. See PROF-11068.
          "ERROR com.datadog.profiling.controller.ProfilingSystem - Fatal exception in profiling"
              + " thread, trying to continue",
          // FIXME: Flaky profiler exception. See PROF-11072.
          "ERROR com.datadog.profiling.controller.ProfilingSystem - Fatal exception during"
              + " profiling startup",
          // FIXME: Flaky on Spring Boot (e.g. IastSpringBootSmokeTest) and other HTTP-client
          // suites.
          "I/O reactor terminated abnormally",
          // FIXME: Observed in WildflySmokeTest (semeru8): a successful JMX collector exit.
          "ERROR datadog.trace.agent.jmxfetch.JMXFetch - jmx collector exited with result: 0");

  private final String name;
  private final String jar;
  private final String mainClass;
  private final String classpath;
  private final List<String> jvmArgs;
  private final List<String> programArgs;
  private final Map<String, Supplier<String>> placeholders;
  private final Map<String, String> extraEnv;
  private final File workingDirectory;
  private final AgentBackend backend;
  private final String agentJar; // null => launch without -javaagent
  private final long startupTimeoutSeconds;
  private final Predicate<String> errorLogFilter;
  private final boolean checkErrorLogs;
  private final boolean checkTelemetry;
  private final boolean applyMemoryTuning;
  private final boolean debugLogs;

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
    this.agentJar = builder.resolveAgentJar();
    this.startupTimeoutSeconds = builder.startupTimeoutSeconds;
    this.checkErrorLogs = builder.checkErrorLogs;
    this.checkTelemetry = builder.checkTelemetry;
    this.applyMemoryTuning = builder.applyMemoryTuning;
    this.debugLogs = builder.debugLogs;
    this.errorLogFilter =
        builder.errorLogFilter != null
            ? builder.errorLogFilter
            : defaultErrorLogFilter(builder.allowedErrorLogs);
  }

  // --- Handle API (field access) ---

  /**
   * Returns the trace query/assert facade of this app's backend.
   *
   * @return The {@link Traces} facade of this app's backend.
   */
  public Traces traces() {
    return this.backend.traces();
  }

  /**
   * Returns the backend this app sends traces to.
   *
   * @return The {@link AgentBackend} this app sends traces to.
   */
  public AgentBackend backend() {
    return this.backend;
  }

  /**
   * Waits (up to the log helper's timeout) for a captured stdout/stderr line matching the given
   * predicate. Captured lines are reset per test method.
   *
   * @param predicate The predicate a captured log line must satisfy.
   * @return {@code true} if a matching line was seen before the timeout, {@code false} otherwise.
   */
  public boolean awaitLogLine(Function<String, Boolean> predicate) {
    try {
      return this.outputThreads.processTestLogLines(predicate);
    } catch (TimeoutException e) {
      return false;
    }
  }

  /**
   * Clears captured stdout/stderr so a subsequent {@link #awaitLogLine} sees only output produced
   * afterward.
   */
  protected final void clearCapturedLogs() {
    this.outputThreads.clearMessages();
  }

  // --- Shared state exposed to subclasses (start-up / per-method hooks) ---

  /**
   * Returns the app's (log/diagnostic) name.
   *
   * @return The app's (log/diagnostic) name.
   */
  protected final String name() {
    return this.name;
  }

  /**
   * Returns the launched process.
   *
   * @return The launched process, or {@code null} before {@link #beforeAll}.
   */
  protected final Process process() {
    return this.process;
  }

  /**
   * Returns how long start-up may wait for the app to become ready.
   *
   * @return The start-up timeout, in seconds.
   */
  protected final long startupTimeoutSeconds() {
    return this.startupTimeoutSeconds;
  }

  /**
   * Registers an additional launch-time placeholder (e.g. a subclass's {@code ${app.httpPort}}).
   *
   * @param token The literal placeholder token to replace (e.g. {@code ${app.httpPort}}).
   * @param value Supplies the replacement value, resolved when the app launches.
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
  }

  @Override
  public final void afterEach(ExtensionContext context) {
    onAfterEach();
    // Check telemetry once, here, while the app and backend are still up — afterAll is too late
    // (the app is killed, and the per-method session clear may have wiped a once-only app-started).
    // Only for an agent-instrumented app on an owned backend (a shared session mixes apps).
    if (this.checkTelemetry
        && this.agentJar != null
        && !this.backend.isShared()
        && !this.telemetryChecked) {
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
        if (!this.backend.isShared()) {
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
   * Invoked once right after the app process is launched (in {@link #beforeAll}) for subclasses to
   * assert their notion of application readiness.
   */
  protected void onStarted() {}

  /** Invoked before each test method (in {@link #beforeEach}) for the app's per-test reset. */
  protected void onBeforeEach() {}

  /**
   * Invoked after each test method (in {@link #afterEach}) for subclasses to assert their notion of
   * application health once the test body has run.
   */
  protected void onAfterEach() {}

  private void launch() throws IOException {
    List<String> command = javaCommand();
    appendLogsArguments(command);
    appendAgentArguments(command);
    appendApplicationArguments(command);

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

  private List<String> javaCommand() {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    if (this.applyMemoryTuning) {
      command.add(getMaxMemoryArgumentForFork());
      command.add(getMinMemoryArgumentForFork());
    }
    // Disable CDS to avoid SIGSEGVs on Linux arm64.
    if (OperatingSystem.isLinux() && OperatingSystem.architecture().isArm64()) {
      command.add("-Xshare:off");
    }
    return command;
  }

  private void appendLogsArguments(List<String> command) {
    String logLevel = this.debugLogs ? DEBUG_LOG_LEVEL : DEFAULT_LOG_LEVEL;
    command.add("-Ddatadog.slf4j.simpleLogger.defaultLogLevel=" + logLevel);
    command.add("-Dorg.slf4j.simpleLogger.defaultLogLevel=" + logLevel);
    // Trick to prevent jul preferences file lock issue on forked processes, in particular in CI
    // which
    // runs on Linux and have competing processes trying to write to it, including the Gradle
    // daemon.
    //   Couldn't flush user prefs: java.util.prefs.BackingStoreException: Couldn't get file lock.
    String tmpDir = System.getProperty("java.io.tmpdir");
    String uniqueLock = this.name + "_" + System.nanoTime();
    command.add("-Djava.util.prefs.userRoot=" + tmpDir + "/userPrefs/" + uniqueLock);
  }

  private void appendAgentArguments(List<String> command) {
    if (this.agentJar != null) {
      command.add("-javaagent:" + this.agentJar);
      command.add("-Ddd.agent.host=" + this.backend.url().getHost());
      command.add("-Ddd.trace.agent.port=" + this.backend.port());
      command.add("-Ddd.service.name=" + SERVICE_NAME);
      command.add("-Ddd.env=" + ENV);
      command.add("-Ddd.version=" + VERSION);
      String sessionToken = this.backend.sessionToken();
      if (sessionToken != null) {
        command.add("-Ddd.test.agent.session.token=" + sessionToken);
      }
      if (this.checkTelemetry) {
        // Emit telemetry promptly so app-started is captured before a (long-running server) app is
        // killed at teardown — mirrors the Groovy base's telemetry tests.
        command.add("-Ddd.telemetry.heartbeat.interval=1");
      }
    }
  }

  private void appendApplicationArguments(List<String> command) {
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
        if (!this.process.waitFor(10, SECONDS)) {
          throw new IllegalStateException(
              "App '"
                  + name()
                  + "' did not terminate after destroy. A lingering process may retain"
                  + " its port and files and interfere with later tests");
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      this.process.destroyForcibly();
    }
  }

  private String substitute(String value) {
    String result = value;
    for (Entry<String, Supplier<String>> entry : this.placeholders.entrySet()) {
      String placeholder = entry.getKey();
      if (result.contains(placeholder)) {
        result = result.replace(placeholder, entry.getValue().get());
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
    return new File(dir, logFileName(this.name, Instant.now()));
  }

  /**
   * Builds the per-app log file name, timestamped (UTC) so a retry doesn't clobber the prior run's
   * log.
   *
   * @param name The app's (log/diagnostic) name.
   * @param when The instant to stamp into the file name.
   * @return The timestamped log file name.
   */
  @VisibleForTesting
  static String logFileName(String name, Instant when) {
    return "smoke-app." + name + "." + LOG_FILE_TIMESTAMP.format(when) + ".log";
  }

  /**
   * Asserts the app logged no error lines, per the configured filter. Reads the whole captured log
   * (everything since launch). Auto-invoked at teardown unless {@link Builder#skipErrorLogCheck()}
   * was set; may also be called explicitly mid-run.
   *
   * @throws AssertionError If the captured log contains one or more error lines.
   */
  public void assertNoErrorLogs() {
    if (this.logFile == null) {
      return; // never launched / nothing captured
    }
    try (Stream<String> errorLines =
        Files.lines(this.logFile.toPath(), StandardCharsets.UTF_8).filter(this.errorLogFilter)) {
      String errors = errorLines.map(s -> "\n  " + s).collect(joining());
      if (!errors.isEmpty()) {
        throw new AssertionError("App '" + this.name + "' logged error line(s):" + errors);
      }
    } catch (NoSuchFileException e) {
      return; // no output file was produced
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read app log " + this.logFile, e);
    }
  }

  /**
   * Asserts the app's telemetry pipeline is active — at least one telemetry message reached the
   * backend. Auto-invoked once at the first {@link #afterEach} (while the app + backend are still
   * up) for an agent-instrumented app on an owned backend, unless {@link
   * Builder#skipTelemetryCheck()}. It intentionally asserts "telemetry is flowing" rather than a
   * specific event: the once-only {@code app-started} is fragile under the per-method session
   * clear, whereas heartbeats keep arriving; a test wanting a specific event (or per-app scoping on
   * a shared backend) can assert it with {@code backend().telemetry().waitForFlat(...)}.
   *
   * @throws AssertionError If no telemetry message is received.
   */
  public void assertTelemetryReceived() {
    this.backend.telemetry().waitForCount(1);
  }

  @VisibleForTesting
  static Predicate<String> defaultErrorLogFilter(List<String> allowed) {
    List<String> allowlist = new ArrayList<>(allowed);
    return line -> {
      for (String allowedSubstring : allowlist) {
        if (line.contains(allowedSubstring)) {
          return false;
        }
      }
      for (String knownNoise : DEFAULT_ERROR_LOG_EXCLUSIONS) {
        if (line.contains(knownNoise)) {
          return false;
        }
      }
      // See ProcessManager.isErrorLog()
      return line.contains("ERROR")
          || line.contains("ASSERTION FAILED")
          || line.contains("Failed to handle exception in instrumentation");
    };
  }

  private static String javaExecutable() {
    return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
  }

  /**
   * Fluent builder shared by the concrete apps. Self-typed so every setter returns the concrete
   * builder for chaining and {@code build()} returns the concrete app. Obtain one via {@link
   * SmokeServerApp#named(String)} or {@link SmokeCliApp#named(String)}.
   *
   * @param <A> The concrete app type this builder builds.
   * @param <B> The concrete builder type, returned from every setter for fluent chaining.
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
    private AgentBackend backend;
    private String explicitAgentJar;
    private boolean noAgent;
    private long startupTimeoutSeconds = DEFAULT_STARTUP_TIMEOUT_SECONDS;
    private Predicate<String> errorLogFilter;
    private final List<String> allowedErrorLogs = new ArrayList<>();
    private boolean checkErrorLogs = true;
    private boolean checkTelemetry = true;
    private boolean applyMemoryTuning = true;
    private boolean debugLogs;

    protected Builder(String name) {
      this.name = name;
    }

    /**
     * Returns {@code this} as the concrete builder type, for fluent chaining.
     *
     * @return {@code this} as the concrete builder type.
     */
    protected abstract B self();

    /**
     * Builds the concrete app. Implementations must call {@link #validate()} first.
     *
     * @return The built app.
     */
    public abstract A build();

    /**
     * Runs {@code java -jar <jarPath>}. Mutually exclusive with {@link #mainClass(String)}.
     *
     * @param jarPath The path to the runnable jar.
     * @return This builder, for chaining.
     */
    public B jar(String jarPath) {
      this.jar = jarPath;
      return self();
    }

    /**
     * Runs {@code java -cp <classpath> <mainClass>} (classpath defaults to the current one).
     *
     * @param mainClass The fully-qualified main class to run.
     * @return This builder, for chaining.
     */
    public B mainClass(String mainClass) {
      this.mainClass = mainClass;
      return self();
    }

    /**
     * Sets the classpath for {@link #mainClass(String)} mode; defaults to the launching JVM's
     * classpath.
     *
     * @param classpath The classpath for the launched JVM.
     * @return This builder, for chaining.
     */
    public B classpath(String classpath) {
      this.classpath = classpath;
      return self();
    }

    /**
     * Adds program arguments (after the jar/main class). Supports launch-time {@code ${...}}
     * placeholders (see {@link #placeholder(String, Supplier)}); {@link SmokeServerApp} also
     * provides {@code ${app.httpPort}}.
     *
     * @param args The program arguments to append.
     * @return This builder, for chaining.
     */
    public B args(String... args) {
      this.programArgs.addAll(asList(args));
      return self();
    }

    /**
     * Adds extra JVM arguments (before the jar/main class). Supports the same launch-time {@code
     * ${...}} placeholders as {@link #args(String...)}.
     *
     * @param jvmArgs The JVM arguments to append.
     * @return This builder, for chaining.
     */
    public B jvmArgs(String... jvmArgs) {
      this.jvmArgs.addAll(asList(jvmArgs));
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
     *
     * @param name The placeholder name; matched as <code>${name}</code> in args and jvmArgs.
     * @param value Supplies the replacement value, resolved when the app launches.
     * @return This builder, for chaining.
     */
    public B placeholder(String name, Supplier<String> value) {
      this.placeholders.put("${" + name + "}", value);
      return self();
    }

    /**
     * Sets an environment variable for the launched process (applied after the defaults).
     *
     * @param key The environment variable name.
     * @param value The environment variable value.
     * @return This builder, for chaining.
     */
    public B env(String key, String value) {
      this.extraEnv.put(key, value);
      return self();
    }

    /**
     * Sets the working directory for the launched process.
     *
     * @param workingDirectory The working directory.
     * @return This builder, for chaining.
     */
    public B workingDirectory(File workingDirectory) {
      this.workingDirectory = workingDirectory;
      return self();
    }

    /**
     * Sets the backend the app sends traces to.
     *
     * @param backend The trace backend.
     * @return This builder, for chaining.
     */
    public B backend(AgentBackend backend) {
      this.backend = backend;
      return self();
    }

    /**
     * Overrides the agent jar (default: the {@code datadog.smoketest.agent.shadowJar.path}
     * property).
     *
     * @param agentJarPath The path to the agent jar.
     * @return This builder, for chaining.
     */
    public B javaAgent(String agentJarPath) {
      this.explicitAgentJar = agentJarPath;
      return self();
    }

    /**
     * Launches the app without {@code -javaagent} (e.g. for launch-mechanics tests).
     *
     * @return This builder, for chaining.
     */
    public B noAgent() {
      this.noAgent = true;
      return self();
    }

    /**
     * Sets how long start-up waits for the app to become ready (default {@value
     * AbstractSmokeApp#DEFAULT_STARTUP_TIMEOUT_SECONDS}s).
     *
     * @param seconds The start-up timeout, in seconds.
     * @return This builder, for chaining.
     */
    public B startupTimeoutSeconds(long seconds) {
      this.startupTimeoutSeconds = seconds;
      return self();
    }

    /**
     * Overrides how a captured log line is judged an error. Replaces the allowlist.
     *
     * @param isError The predicate reporting whether a captured log line is an error.
     * @return This builder, for chaining.
     */
    public B errorLogFilter(Predicate<String> isError) {
      this.errorLogFilter = isError;
      return self();
    }

    /**
     * Allowlists log lines containing any of these substrings from the default error-log check.
     *
     * @param substrings The substrings whose presence exempts a line from the error check.
     * @return This builder, for chaining.
     */
    public B allowedErrorLogs(String... substrings) {
      this.allowedErrorLogs.addAll(asList(substrings));
      return self();
    }

    /**
     * Disables the automatic no-error-logs check at teardown (e.g. for error-case tests).
     *
     * @return This builder, for chaining.
     */
    public B skipErrorLogCheck() {
      this.checkErrorLogs = false;
      return self();
    }

    /**
     * Disables the automatic app-started telemetry check at teardown (for agent apps that run with
     * telemetry disabled, e.g. {@code dd.instrumentation.telemetry.enabled=false}).
     *
     * @return This builder, for chaining.
     */
    public B skipTelemetryCheck() {
      this.checkTelemetry = false;
      return self();
    }

    /**
     * Disables the default child-JVM heap bounds ({@code -Xmx}/{@code -Xms} from {@link
     * ForkedTestUtils}). You can instead override the values by passing {@code -Xmx}/{@code -Xms}
     * to {@link #jvmArgs(String...)} (those take precedence).
     *
     * @return This builder, for chaining.
     */
    public B skipMemoryTuning() {
      this.applyMemoryTuning = false;
      return self();
    }

    /**
     * Runs the app with {@code debug} tracer and application log levels instead of the default
     * {@code info}, for tests asserting on debug output or diagnosing a failure.
     *
     * @return This builder, for chaining.
     */
    public B debugLogs() {
      this.debugLogs = true;
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
        throw new IllegalStateException(
            "A AgentBackend is required. Use backend(...) to build your app");
      }
      if ((this.jar == null) == (this.mainClass == null)) {
        throw new IllegalStateException("Exactly one of jar(...) or mainClass(...) must be set");
      }
      if (!this.noAgent && resolveAgentJar() == null) {
        throw new IllegalStateException(
            "Agent jar not found: system property '"
                + AGENT_JAR_PROPERTY
                + "' is not set. Gradle sets it automatically; on other runners call javaAgent(path)"
                + " to point at an agent jar, or noAgent() to run without the tracer.");
      }
      // TODO deferred: profiling args; crash-tracking args (-XX:OnError=...dd_crash_uploader.sh);
      // All reachable today via .jvmArgs() add dedicated opt-ins when a ported test needs them.
    }
  }
}
