package datadog.smoketest;

import datadog.smoketest.backend.TraceBackend;
import datadog.smoketest.backend.Traces;
import datadog.trace.agent.test.utils.PortUtils;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.extension.AfterAllCallback;
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
    implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, ParameterResolver {

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
  private final Map<String, String> extraEnv;
  private final File workingDirectory;
  private final TraceBackend backend;
  private final boolean ownsBackend;
  private final String agentJar; // null => launch without -javaagent
  private final boolean server; // wait for the HTTP port to open on start
  private final long startupTimeoutSeconds;
  private final int httpPort;

  private final OkHttpClient httpClient = new OkHttpClient();
  private final OutputThreads outputThreads = new OutputThreads();
  private Process process;

  private SmokeApp(Builder builder) {
    this.name = builder.name;
    this.jar = builder.jar;
    this.mainClass = builder.mainClass;
    this.classpath =
        builder.classpath != null ? builder.classpath : System.getProperty("java.class.path");
    this.jvmArgs = new ArrayList<>(builder.jvmArgs);
    this.programArgs = new ArrayList<>(builder.programArgs);
    this.extraEnv = new HashMap<>(builder.extraEnv);
    this.workingDirectory = builder.workingDirectory;
    this.backend = builder.backend;
    this.ownsBackend = !builder.backend.isShared();
    this.agentJar = builder.resolveAgentJar();
    this.server = builder.server;
    this.startupTimeoutSeconds = builder.startupTimeoutSeconds;
    this.httpPort = PortUtils.randomOpenPort();
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
    return httpPort;
  }

  /** Base URL of the app's HTTP server. */
  public URI url() {
    return URI.create("http://localhost:" + httpPort);
  }

  /**
   * Issues a GET to the app and returns the HTTP status code (the response is drained and closed).
   */
  public int get(String path) {
    String full = url() + (path.startsWith("/") ? path : "/" + path);
    Request request = new Request.Builder().url(full).get().build();
    try (Response response = httpClient.newCall(request).execute()) {
      return response.code();
    } catch (IOException e) {
      throw new IllegalStateException("GET " + full + " failed", e);
    }
  }

  /** The trace query/assert facade of this app's backend. */
  public Traces traces() {
    return backend.traces();
  }

  /** The backend this app sends traces to. */
  public TraceBackend backend() {
    return backend;
  }

  /**
   * Waits (up to the log helper's timeout) for a captured stdout/stderr line matching {@code
   * predicate}; returns {@code false} on timeout. Lines are reset per test method.
   */
  public boolean awaitLogLine(Function<String, Boolean> predicate) {
    try {
      return outputThreads.processTestLogLines(predicate);
    } catch (TimeoutException e) {
      return false;
    }
  }

  // --- Lifecycle (per-class start, per-method reset, teardown) ---

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    if (ownsBackend) {
      backend.start();
    }
    launch();
    if (server) {
      PortUtils.waitForPortToOpen(httpPort, startupTimeoutSeconds, TimeUnit.SECONDS, process);
    } else if (!process.isAlive() && process.exitValue() != 0) {
      throw new IllegalStateException(
          "App '" + name + "' exited abnormally on start (exit " + process.exitValue() + ")");
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    if (process == null || !process.isAlive()) {
      throw new IllegalStateException("App '" + name + "' is not alive at the start of a test");
    }
    outputThreads.clearMessages();
    if (ownsBackend) {
      backend.clear();
    }
  }

  @Override
  public void afterAll(ExtensionContext context) {
    try {
      stopProcess();
    } finally {
      outputThreads.close();
      if (ownsBackend) {
        backend.close();
      }
    }
  }

  private void launch() throws IOException {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());

    if (agentJar != null) {
      command.add("-javaagent:" + agentJar);
      command.add("-Ddd.trace.agent.host=" + backend.url().getHost());
      command.add("-Ddd.trace.agent.port=" + backend.port());
      command.add("-Ddd.service.name=" + SERVICE_NAME);
      command.add("-Ddd.env=" + ENV);
      command.add("-Ddd.version=" + VERSION);
      String sessionToken = backend.sessionToken();
      if (sessionToken != null) {
        command.add("-Ddd.trace.agent.test.session.token=" + sessionToken);
      }
    }
    for (String jvmArg : jvmArgs) {
      command.add(substitute(jvmArg));
    }
    if (jar != null) {
      command.add("-jar");
      command.add(jar);
    } else {
      command.add("-cp");
      command.add(classpath);
      command.add(mainClass);
    }
    for (String programArg : programArgs) {
      command.add(substitute(programArg));
    }

    ProcessBuilder processBuilder = new ProcessBuilder(command);
    if (workingDirectory != null) {
      processBuilder.directory(workingDirectory);
    }
    Map<String, String> env = processBuilder.environment();
    env.put("JAVA_HOME", System.getProperty("java.home"));
    env.put("DD_API_KEY", API_KEY);
    env.keySet().removeAll(NOISY_ENVIRONMENT_VARIABLES);
    env.putAll(extraEnv);
    processBuilder.redirectErrorStream(true);

    process = processBuilder.start();
    outputThreads.captureOutput(process, logFile());
  }

  private void stopProcess() {
    if (process == null) {
      return;
    }
    if (!process.isAlive()) {
      return;
    }
    process.destroy();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  private String substitute(String value) {
    return value.replace(HTTP_PORT_PLACEHOLDER, Integer.toString(httpPort));
  }

  private File logFile() {
    String buildDir = System.getProperty(BUILD_DIR_PROPERTY);
    File dir =
        buildDir != null
            ? new File(buildDir, "reports")
            : new File(System.getProperty("java.io.tmpdir"));
    dir.mkdirs();
    // TODO Q6 (deferred): retry-safe timestamped log file names so retries don't clobber prior
    // logs.
    return new File(dir, "smoke-app." + name + ".log");
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
      return backend;
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
    private final Map<String, String> extraEnv = new HashMap<>();
    private File workingDirectory;
    private TraceBackend backend;
    private String explicitAgentJar;
    private boolean noAgent;
    private boolean server = true;
    private long startupTimeoutSeconds = 120;

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

    private String resolveAgentJar() {
      if (noAgent) {
        return null;
      }
      return explicitAgentJar != null ? explicitAgentJar : System.getProperty(AGENT_JAR_PROPERTY);
    }

    public SmokeApp build() {
      if (backend == null) {
        throw new IllegalStateException("A TraceBackend is required — call backend(...)");
      }
      if ((jar == null) == (mainClass == null)) {
        throw new IllegalStateException("Exactly one of jar(...) or mainClass(...) must be set");
      }
      // TODO Q6 (deferred, opt-in mixins / .jvmArgs(...) escape hatch — not baked into the base):
      //  profiling args; crash-tracking args (-XX:OnError=...dd_crash_uploader.sh); memory tuning
      //  (ForkedTestUtils); error-log assertion helpers (assertNoErrorLogs + FIXME allowlist);
      //  retry log-file timestamping. Add as explicit opt-ins when a ported test needs them.
      return new SmokeApp(this);
    }
  }
}
