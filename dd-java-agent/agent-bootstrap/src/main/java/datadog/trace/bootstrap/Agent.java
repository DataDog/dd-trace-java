package datadog.trace.bootstrap;

import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static datadog.environment.JavaVirtualMachine.isOracleJDK8;
import static datadog.trace.api.ConfigDefaults.DEFAULT_STARTUP_LOGS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_COMMAND_PATTERN;
import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_ENABLED;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static datadog.trace.bootstrap.Library.WILDFLY;
import static datadog.trace.bootstrap.Library.detectLibraries;
import static datadog.trace.bootstrap.config.provider.StableConfigSource.FLEET;
import static datadog.trace.bootstrap.config.provider.StableConfigSource.LOCAL;
import static datadog.trace.util.AgentThreadFactory.AgentThread.JMX_STARTUP;
import static datadog.trace.util.AgentThreadFactory.AgentThread.PROFILER_STARTUP;
import static datadog.trace.util.AgentThreadFactory.AgentThread.TRACE_STARTUP;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static datadog.trace.util.ConfigStrings.propertyNameToSystemPropertyName;
import static datadog.trace.util.ConfigStrings.toEnvVar;

import datadog.environment.EnvironmentVariables;
import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import datadog.environment.SystemProperties;
import datadog.instrument.classinject.ClassInjector;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import datadog.trace.api.StatsDClientManager;
import datadog.trace.api.WithGlobalTracer;
import datadog.trace.api.appsec.AppSecEventTracker;
import datadog.trace.api.config.AppSecConfig;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.api.config.CrashTrackingConfig;
import datadog.trace.api.config.CwsConfig;
import datadog.trace.api.config.DebuggerConfig;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.IastConfig;
import datadog.trace.api.config.JmxFetchConfig;
import datadog.trace.api.config.LlmObsConfig;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.config.RemoteConfigConfig;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.config.UsmConfig;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.git.EmbeddedGitInfoBuilder;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.api.profiling.ProfilingEnablement;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.bootstrap.benchmark.StaticEventLogger;
import datadog.trace.bootstrap.config.provider.StableConfigSource;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.bootstrap.instrumentation.api.WriterConstants;
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.AgentThreadFactory.AgentThread;
import datadog.trace.util.throwable.FatalAgentMisconfigurationError;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent start up logic.
 *
 * <p>This class is loaded and called by {@code datadog.trace.bootstrap.AgentBootstrap}
 *
 * <p>The intention is for this class to be loaded by bootstrap classloader to make sure we have
 * unimpeded access to the rest of Datadog's agent parts.
 */
public class Agent {

  private static final String SIMPLE_LOGGER_SHOW_DATE_TIME_PROPERTY =
      "datadog.slf4j.simpleLogger.showDateTime";
  private static final String SIMPLE_LOGGER_JSON_ENABLED_PROPERTY =
      "datadog.slf4j.simpleLogger.jsonEnabled";
  private static final String SIMPLE_LOGGER_DATE_TIME_FORMAT_JSON_DEFAULT =
      "yyyy-MM-dd'T'HH:mm:ss.SSSZ";
  private static final String SIMPLE_LOGGER_DATE_TIME_FORMAT_PROPERTY =
      "datadog.slf4j.simpleLogger.dateTimeFormat";
  private static final String SIMPLE_LOGGER_DATE_TIME_FORMAT_DEFAULT =
      "'[dd.trace 'yyyy-MM-dd HH:mm:ss:SSS Z']'";
  private static final String SIMPLE_LOGGER_DEFAULT_LOG_LEVEL_PROPERTY =
      "datadog.slf4j.simpleLogger.defaultLogLevel";

  private static final String AGENT_INSTALLER_CLASS_NAME =
      "datadog.trace.agent.tooling.AgentInstaller";

  private static final int DEFAULT_JMX_START_DELAY = 15; // seconds

  private static final Logger log;

  private enum AgentFeature {
    TRACING(TraceInstrumentationConfig.TRACE_ENABLED, true),
    JMXFETCH(JmxFetchConfig.JMX_FETCH_ENABLED, true),
    STARTUP_LOGS(GeneralConfig.STARTUP_LOGS_ENABLED, DEFAULT_STARTUP_LOGS_ENABLED),
    CRASH_TRACKING(
        CrashTrackingConfig.CRASH_TRACKING_ENABLED,
        CrashTrackingConfig.CRASH_TRACKING_ENABLED_DEFAULT),
    PROFILING(ProfilingConfig.PROFILING_ENABLED, false),
    APPSEC(AppSecConfig.APPSEC_ENABLED, false),
    IAST(IastConfig.IAST_ENABLED, false),
    REMOTE_CONFIG(RemoteConfigConfig.REMOTE_CONFIGURATION_ENABLED, true),
    DEPRECATED_REMOTE_CONFIG(RemoteConfigConfig.REMOTE_CONFIG_ENABLED, true),
    CWS(CwsConfig.CWS_ENABLED, false),
    CIVISIBILITY(CiVisibilityConfig.CIVISIBILITY_ENABLED, false),
    CIVISIBILITY_AGENTLESS(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED, false),
    USM(UsmConfig.USM_ENABLED, false),
    TELEMETRY(GeneralConfig.TELEMETRY_ENABLED, true),
    DYNAMIC_INSTRUMENTATION(DebuggerConfig.DYNAMIC_INSTRUMENTATION_ENABLED, false),
    EXCEPTION_REPLAY(DebuggerConfig.EXCEPTION_REPLAY_ENABLED, false),
    CODE_ORIGIN(TraceInstrumentationConfig.CODE_ORIGIN_FOR_SPANS_ENABLED, false),
    DATA_JOBS(GeneralConfig.DATA_JOBS_ENABLED, false),
    AGENTLESS_LOG_SUBMISSION(GeneralConfig.AGENTLESS_LOG_SUBMISSION_ENABLED, false),
    LLMOBS(LlmObsConfig.LLMOBS_ENABLED, false),
    LLMOBS_AGENTLESS(LlmObsConfig.LLMOBS_AGENTLESS_ENABLED, false);

    private final String configKey;
    private final String systemProp;
    private final boolean enabledByDefault;

    AgentFeature(final String configKey, final boolean enabledByDefault) {
      this.configKey = configKey;
      this.systemProp = propertyNameToSystemPropertyName(configKey);
      this.enabledByDefault = enabledByDefault;
    }

    public String getConfigKey() {
      return configKey;
    }

    public String getSystemProp() {
      return systemProp;
    }

    public boolean isEnabledByDefault() {
      return enabledByDefault;
    }
  }

  static {
    // We can configure logger here because datadog.trace.agent.AgentBootstrap doesn't touch it.
    configureLogger();
    log = LoggerFactory.getLogger(Agent.class);
  }

  private static final AtomicBoolean jmxStarting = new AtomicBoolean();

  // fields must be managed under class lock
  private static ClassLoader AGENT_CLASSLOADER = null;

  private static volatile Runnable PROFILER_INIT_AFTER_JMX = null;
  private static volatile Runnable CRASHTRACKER_INIT_AFTER_JMX = null;

  private static boolean jmxFetchEnabled = true;
  private static boolean profilingEnabled = false;
  private static boolean crashTrackingEnabled = false;
  private static boolean appSecEnabled;
  private static boolean appSecFullyDisabled;
  private static boolean remoteConfigEnabled = true;
  private static boolean iastEnabled = false;
  private static boolean iastFullyDisabled;
  private static boolean cwsEnabled = false;
  private static boolean ciVisibilityEnabled = false;
  private static boolean llmObsEnabled = false;
  private static boolean llmObsAgentlessEnabled = false;
  private static boolean usmEnabled = false;
  private static boolean telemetryEnabled = true;
  private static boolean flareEnabled = true;
  private static boolean dynamicInstrumentationEnabled = false;
  private static boolean exceptionReplayEnabled = false;
  private static boolean codeOriginEnabled = false;
  private static boolean distributedDebuggerEnabled = false;
  private static boolean agentlessLogSubmissionEnabled = false;

  private static void safelySetContextClassLoader(ClassLoader classLoader) {
    try {
      // this method call can cause a SecurityException if a security manager is installed.
      Thread.currentThread().setContextClassLoader(classLoader);
    } catch (final Throwable ignored) {
    }
  }

  /**
   * Starts the agent; returns a boolean indicating if Agent started successfully
   *
   * <p>The Agent is considered to start successfully if Instrumentation can be activated. All other
   * pieces are considered optional.
   */
  @SuppressFBWarnings("AT_STALE_THREAD_WRITE_OF_PRIMITIVE")
  public static void start(
      final Object bootstrapInitTelemetry,
      final Instrumentation inst,
      final URL agentJarURL,
      final String agentArgs) {
    InitializationTelemetry initTelemetry = InitializationTelemetry.proxy(bootstrapInitTelemetry);

    StaticEventLogger.begin("Agent");
    StaticEventLogger.begin("Agent.start");

    try {
      ClassInjector.enableClassInjection(inst);
    } catch (Throwable e) {
      log.debug("Instrumentation-based class injection is not available", e);
      setSystemPropertyDefault(
          propertyNameToSystemPropertyName(TraceInstrumentationConfig.UNSAFE_CLASS_INJECTION),
          "true");
    }

    createAgentClassloader(agentJarURL);

    if (Platform.isNativeImageBuilder()) {
      // these default services are not used during native-image builds
      remoteConfigEnabled = false;
      telemetryEnabled = false;
      flareEnabled = false;
      // apply trace instrumentation, but skip other products at native-image build time
      startDatadogAgent(initTelemetry, inst);
      StaticEventLogger.end("Agent.start");
      return;
    }

    if (agentArgs != null && !agentArgs.isEmpty()) {
      injectAgentArgsConfig(agentArgs);
    }

    configureCiVisibility(agentJarURL);

    // Halt agent start if DJM is enabled and is not successfully configure
    if (!configureDataJobsMonitoring()) {
      return;
    }

    if (!isSupportedAppSecArch()) {
      log.debug(
          "OS and architecture ({}/{}) not supported by AppSec, dd.appsec.enabled will default to false",
          SystemProperties.get("os.name"),
          SystemProperties.get("os.arch"));
      setSystemPropertyDefault(AgentFeature.APPSEC.getSystemProp(), "false");
    }

    jmxFetchEnabled = isFeatureEnabled(AgentFeature.JMXFETCH);
    profilingEnabled = isFeatureEnabled(AgentFeature.PROFILING);
    crashTrackingEnabled = isFeatureEnabled(AgentFeature.CRASH_TRACKING);
    usmEnabled = isFeatureEnabled(AgentFeature.USM);
    appSecEnabled = isFeatureEnabled(AgentFeature.APPSEC);
    appSecFullyDisabled = isFullyDisabled(AgentFeature.APPSEC);
    iastEnabled = isFeatureEnabled(AgentFeature.IAST);
    iastFullyDisabled = isIastFullyDisabled(appSecEnabled);
    remoteConfigEnabled =
        isFeatureEnabled(AgentFeature.REMOTE_CONFIG)
            || isFeatureEnabled(AgentFeature.DEPRECATED_REMOTE_CONFIG);
    cwsEnabled = isFeatureEnabled(AgentFeature.CWS);
    telemetryEnabled = isFeatureEnabled(AgentFeature.TELEMETRY);
    dynamicInstrumentationEnabled = isFeatureEnabled(AgentFeature.DYNAMIC_INSTRUMENTATION);
    exceptionReplayEnabled = isFeatureEnabled(AgentFeature.EXCEPTION_REPLAY);
    codeOriginEnabled = isFeatureEnabled(AgentFeature.CODE_ORIGIN);
    agentlessLogSubmissionEnabled = isFeatureEnabled(AgentFeature.AGENTLESS_LOG_SUBMISSION);
    llmObsEnabled = isFeatureEnabled(AgentFeature.LLMOBS);

    // setup writers when llmobs is enabled to accomodate apm and llmobs
    if (llmObsEnabled) {
      // for llm obs spans, use agent proxy by default, apm spans will use agent writer
      setSystemPropertyDefault(
          propertyNameToSystemPropertyName(TracerConfig.WRITER_TYPE),
          WriterConstants.MULTI_WRITER_TYPE
              + ":"
              + WriterConstants.DD_INTAKE_WRITER_TYPE
              + ","
              + WriterConstants.DD_AGENT_WRITER_TYPE);
      if (llmObsAgentlessEnabled) {
        // use API writer only
        setSystemPropertyDefault(
            propertyNameToSystemPropertyName(TracerConfig.WRITER_TYPE),
            WriterConstants.DD_INTAKE_WRITER_TYPE);
      }
    }

    boolean retryProfilerStart = false;
    if (profilingEnabled) {
      if (!isOracleJDK8()) {
        // Profiling agent startup code is written in a way to allow `startProfilingAgent` be called
        // multiple times
        // If early profiling is enabled then this call will start profiling.
        // If early profiling is disabled then later call will do this.
        retryProfilerStart = startProfilingAgent(true, true, inst);
      } else {
        log.debug("Oracle JDK 8 detected. Delaying profiler initialization.");
        // Profiling can not run early on Oracle JDK 8 because it will cause JFR initialization
        // deadlock.
        // Oracle JDK 8 JFR controller requires JMX so register an 'after-jmx-initialized' callback.
        PROFILER_INIT_AFTER_JMX = () -> startProfilingAgent(false, true, inst);
      }
    }

    if (cwsEnabled) {
      startCwsAgent();
    }

    /*
     * Force the task scheduler init early. The exception profiling instrumentation may get in way of the initialization
     * when it will happen after the class transformers were added.
     */
    AgentTaskScheduler.initialize();

    // We need to run the crashtracking initialization after all the config has been resolved and
    // task scheduler initialized
    if (crashTrackingEnabled) {
      StaticEventLogger.begin("crashtracking");
      startCrashTracking();
      StaticEventLogger.end("crashtracking");
    }
    startDatadogAgent(initTelemetry, inst);

    final EnumSet<Library> libraries = detectLibraries(log);

    final boolean appUsingCustomLogManager = isAppUsingCustomLogManager(libraries);
    final boolean appUsingCustomJMXBuilder = isAppUsingCustomJMXBuilder(libraries);

    /*
     * java.util.logging.LogManager maintains a final static LogManager, which is created during class initialization.
     *
     * JMXFetch uses jre bootstrap classes which touch this class. This means applications which require a custom log
     * manager may not have a chance to set the global log manager if jmxfetch runs first. JMXFetch will incorrectly
     * set the global log manager in cases where the app sets the log manager system property or when the log manager
     * class is not on the system classpath.
     *
     * Our solution is to delay the initialization of jmxfetch when we detect a custom log manager being used.
     *
     * Once we see the LogManager class loading, it's safe to start jmxfetch because the application is already setting
     * the global log manager and jmxfetch won't be able to touch it due to classloader locking.
     *
     * Likewise if a custom JMX builder is configured which is not on the system classpath then we delay starting
     * JMXFetch until we detect the custom MBeanServerBuilder is being used. This takes precedence over the custom
     * log manager check because any custom log manager will be installed before any custom MBeanServerBuilder.
     */
    if (jmxFetchEnabled || profilingEnabled) { // both features use JMX
      int jmxStartDelay = getJmxStartDelay();
      if (appUsingCustomJMXBuilder) {
        log.debug("Custom JMX builder detected. Delaying JMXFetch initialization.");
        registerMBeanServerBuilderCallback(new StartJmxCallback(jmxStartDelay));
      } else if (appUsingCustomLogManager) {
        log.debug("Custom logger detected. Delaying JMXFetch initialization.");
        registerLogManagerCallback(new StartJmxCallback(jmxStartDelay));
      } else {
        scheduleJmxStart(jmxStartDelay);
      }
    }

    /*
     * Similar thing happens with DatadogTracer on (at least) zulu-8 because it uses OkHttp which indirectly loads JFR
     * events which in turn loads LogManager. This is not a problem on newer JDKs because there JFR uses different
     * logging facility. Likewise on IBM JDKs OkHttp may indirectly load 'IBMSASL' which in turn loads LogManager.
     */
    boolean delayOkHttp = !ciVisibilityEnabled && okHttpMayIndirectlyLoadJUL();
    boolean waitForJUL = appUsingCustomLogManager && delayOkHttp;
    int okHttpDelayMillis;
    if (waitForJUL) {
      okHttpDelayMillis = 1_000;
    } else if (delayOkHttp) {
      okHttpDelayMillis = 100;
    } else {
      okHttpDelayMillis = 0;
    }

    InstallDatadogTracerCallback installDatadogTracerCallback =
        new InstallDatadogTracerCallback(initTelemetry, inst, okHttpDelayMillis);
    if (waitForJUL) {
      log.debug("Custom logger detected. Delaying Datadog Tracer initialization.");
      registerLogManagerCallback(installDatadogTracerCallback);
    } else if (okHttpDelayMillis > 0) {
      installDatadogTracerCallback.run(); // complete on different thread (after premain)
    } else {
      installDatadogTracerCallback.execute(); // complete on primordial thread in premain
    }

    /*
     * Similar thing happens with Profiler on zulu-8 because it is using OkHttp which indirectly loads JFR events which
     * in turn loads LogManager. This is not a problem on newer JDKs because there JFR uses different logging facility.
     */
    if (profilingEnabled && !isOracleJDK8()) {
      StaticEventLogger.begin("Profiling");

      if (waitForJUL) {
        log.debug("Custom logger detected. Delaying Profiling initialization.");
        registerLogManagerCallback(new StartProfilingAgentCallback(inst));
      } else {
        startProfilingAgent(false, retryProfilerStart, inst);
        // only enable instrumentation based profilers when we know JFR is ready
        InstrumentationBasedProfiling.enableInstrumentationBasedProfiling();
      }

      StaticEventLogger.end("Profiling");
    }

    StaticEventLogger.end("Agent.start");
  }

  private static boolean configureDataJobsMonitoring() {
    boolean dataJobsEnabled = isFeatureEnabled(AgentFeature.DATA_JOBS);
    if (dataJobsEnabled) {
      log.info("Data Jobs Monitoring enabled, enabling spark integrations");

      setSystemPropertyDefault(
          propertyNameToSystemPropertyName(TracerConfig.TRACE_LONG_RUNNING_ENABLED), "true");
      setSystemPropertyDefault(
          propertyNameToSystemPropertyName("integration.spark.enabled"), "true");
      setSystemPropertyDefault(
          propertyNameToSystemPropertyName("integration.spark-executor.enabled"), "true");
      // needed for e2e pipeline
      setSystemPropertyDefault(propertyNameToSystemPropertyName("data.streams.enabled"), "true");
      setSystemPropertyDefault(
          propertyNameToSystemPropertyName("integration.aws-sdk.enabled"), "true");
      setSystemPropertyDefault(
          propertyNameToSystemPropertyName("integration.kafka.enabled"), "true");

      if ("true".equals(ddGetProperty(propertyNameToSystemPropertyName(DATA_JOBS_ENABLED)))) {
        setSystemPropertyDefault(
            propertyNameToSystemPropertyName("integration.spark-openlineage.enabled"), "true");
      }

      String javaCommand = String.join(" ", JavaVirtualMachine.getCommandArguments());
      String dataJobsCommandPattern =
          ddGetProperty(propertyNameToSystemPropertyName(DATA_JOBS_COMMAND_PATTERN));
      if (!isDataJobsSupported(javaCommand, dataJobsCommandPattern)) {
        log.warn(
            "Data Jobs Monitoring is not compatible with non-spark command {} based on command pattern {}. dd-trace-java will not be installed",
            javaCommand,
            dataJobsCommandPattern);
        return false;
      }
    }
    return true;
  }

  private static void injectAgentArgsConfig(String agentArgs) {
    try {
      final Class<?> agentArgsInjectorClass =
          AGENT_CLASSLOADER.loadClass("datadog.trace.bootstrap.config.provider.AgentArgsInjector");
      final Method registerCallbackMethod =
          agentArgsInjectorClass.getMethod("injectAgentArgsConfig", String.class);
      registerCallbackMethod.invoke(null, agentArgs);
    } catch (final Exception ex) {
      log.error("Error injecting agent args config {}", agentArgs, ex);
    }
  }

  @SuppressFBWarnings("AT_STALE_THREAD_WRITE_OF_PRIMITIVE")
  private static void configureCiVisibility(URL agentJarURL) {
    // Retro-compatibility for the old way to configure CI Visibility
    if ("true".equals(ddGetProperty("dd.integration.junit.enabled"))
        || "true".equals(ddGetProperty("dd.integration.testng.enabled"))) {
      setSystemPropertyDefault(AgentFeature.CIVISIBILITY.getSystemProp(), "true");
    }

    ciVisibilityEnabled = isFeatureEnabled(AgentFeature.CIVISIBILITY);
    if (ciVisibilityEnabled) {
      // if CI Visibility is enabled, all the other features are disabled by default
      // unless the user had explicitly enabled them.
      setSystemPropertyDefault(AgentFeature.JMXFETCH.getSystemProp(), "false");
      setSystemPropertyDefault(AgentFeature.PROFILING.getSystemProp(), "false");
      setSystemPropertyDefault(AgentFeature.APPSEC.getSystemProp(), "false");
      setSystemPropertyDefault(AgentFeature.IAST.getSystemProp(), "false");
      setSystemPropertyDefault(AgentFeature.REMOTE_CONFIG.getSystemProp(), "false");
      setSystemPropertyDefault(AgentFeature.CWS.getSystemProp(), "false");

      /*if CI Visibility is enabled, the PrioritizationType should be {@code Prioritization.ENSURE_TRACE} */
      setSystemPropertyDefault(
          propertyNameToSystemPropertyName(TracerConfig.PRIORITIZATION_TYPE), "ENSURE_TRACE");

      try {
        setSystemPropertyDefault(
            propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_AGENT_JAR_URI),
            agentJarURL.toURI().toString());
      } catch (URISyntaxException e) {
        throw new IllegalArgumentException(
            "Could not create URI from agent JAR URL: " + agentJarURL, e);
      }
    }

    // Enable automatic fetching of git tags from datadog_git.properties only if CI Visibility is
    // not enabled
    if (!ciVisibilityEnabled) {
      GitInfoProvider.INSTANCE.registerGitInfoBuilder(new EmbeddedGitInfoBuilder());
    }
  }

  public static void shutdown(final boolean sync) {
    StaticEventLogger.end("Agent");
    StaticEventLogger.stop();

    if (profilingEnabled) {
      shutdownProfilingAgent(sync);
    }
    if (telemetryEnabled) {
      stopTelemetry();
    }
    if (flareEnabled) {
      stopFlarePoller();
    }

    if (agentlessLogSubmissionEnabled) {
      shutdownLogsIntake();
    }
  }

  public static synchronized Class<?> installAgentCLI() throws Exception {
    if (null == AGENT_CLASSLOADER) {
      // in CLI mode we skip installation of instrumentation because we're not running as an agent
      // we still create the agent classloader so we can install the tracer and query integrations
      CodeSource codeSource = Agent.class.getProtectionDomain().getCodeSource();
      if (codeSource == null || codeSource.getLocation() == null) {
        throw new MalformedURLException("Could not get jar location from code source");
      }
      createAgentClassloader(codeSource.getLocation());
    }
    return AGENT_CLASSLOADER.loadClass("datadog.trace.agent.tooling.AgentCLI");
  }

  /** Used by AgentCLI to send sample traces from the command-line. */
  public static void startDatadogTracer(InitializationTelemetry initTelemetry) throws Exception {
    Class<?> scoClass =
        AGENT_CLASSLOADER.loadClass("datadog.communication.ddagent.SharedCommunicationObjects");
    installDatadogTracer(initTelemetry, scoClass, scoClass.getConstructor().newInstance());
    startJmx(); // send runtime metrics along with the traces
  }

  private static void registerLogManagerCallback(final ClassLoadCallBack callback) {
    // one minute fail-safe in case the class was unintentionally loaded during premain
    AgentTaskScheduler.get().schedule(callback, 1, TimeUnit.MINUTES);
    try {
      final Class<?> agentInstallerClass = AGENT_CLASSLOADER.loadClass(AGENT_INSTALLER_CLASS_NAME);
      final Method registerCallbackMethod =
          agentInstallerClass.getMethod("registerClassLoadCallback", String.class, Runnable.class);
      registerCallbackMethod.invoke(null, "java.util.logging.LogManager", callback);
    } catch (final Exception ex) {
      log.error("Error registering callback for {}", callback.agentThread(), ex);
    }
  }

  private static void registerMBeanServerBuilderCallback(final ClassLoadCallBack callback) {
    // one minute fail-safe in case the class was unintentionally loaded during premain
    AgentTaskScheduler.get().schedule(callback, 1, TimeUnit.MINUTES);
    try {
      final Class<?> agentInstallerClass = AGENT_CLASSLOADER.loadClass(AGENT_INSTALLER_CLASS_NAME);
      final Method registerCallbackMethod =
          agentInstallerClass.getMethod("registerClassLoadCallback", String.class, Runnable.class);
      registerCallbackMethod.invoke(null, "javax.management.MBeanServerBuilder", callback);
    } catch (final Exception ex) {
      log.error("Error registering callback for {}", callback.agentThread(), ex);
    }
  }

  protected abstract static class ClassLoadCallBack implements Runnable {
    private final AtomicBoolean starting = new AtomicBoolean();

    @Override
    public void run() {
      if (starting.getAndSet(true)) {
        return; // someone has already called us
      }

      /*
       * This callback is called from within bytecode transformer. This can be a problem if callback tries
       * to load classes being transformed. To avoid this we start a thread here that calls the callback.
       * This seems to resolve this problem.
       */
      final Thread thread =
          newAgentThread(
              agentThread(),
              new Runnable() {
                @Override
                public void run() {
                  try {
                    execute();
                  } catch (final Exception e) {
                    log.error("Failed to run {}", agentThread(), e);
                  }
                }
              });
      thread.start();
    }

    public abstract AgentThread agentThread();

    public abstract void execute();
  }

  protected static class StartJmxCallback extends ClassLoadCallBack {
    private final int jmxStartDelay;

    StartJmxCallback(final int jmxStartDelay) {
      this.jmxStartDelay = jmxStartDelay;
    }

    @Override
    public AgentThread agentThread() {
      return JMX_STARTUP;
    }

    @Override
    public void execute() {
      // still honour the requested delay from the point JMX becomes available
      scheduleJmxStart(jmxStartDelay);
    }
  }

  protected static class InstallDatadogTracerCallback extends ClassLoadCallBack {
    private final Instrumentation instrumentation;
    private final Object sco;
    private final Class<?> scoClass;
    private final int okHttpDelayMillis;

    public InstallDatadogTracerCallback(
        InitializationTelemetry initTelemetry,
        Instrumentation instrumentation,
        int okHttpDelayMillis) {
      this.okHttpDelayMillis = okHttpDelayMillis;
      this.instrumentation = instrumentation;
      try {
        scoClass =
            AGENT_CLASSLOADER.loadClass("datadog.communication.ddagent.SharedCommunicationObjects");
        sco = scoClass.getConstructor(boolean.class).newInstance(okHttpDelayMillis > 0);
      } catch (ClassNotFoundException
          | NoSuchMethodException
          | InstantiationException
          | IllegalAccessException
          | InvocationTargetException e) {
        throw new UndeclaredThrowableException(e);
      }

      installDatadogTracer(initTelemetry, scoClass, sco);
      maybeInstallLogsIntake(scoClass, sco);
      maybeStartIast(instrumentation);
    }

    @Override
    public AgentThread agentThread() {
      return TRACE_STARTUP;
    }

    @Override
    public void execute() {
      if (okHttpDelayMillis > 0) {
        resumeRemoteComponents();
      }

      maybeStartAppSec(scoClass, sco);
      maybeStartCiVisibility(instrumentation, scoClass, sco);
      maybeStartLLMObs(instrumentation, scoClass, sco);
      // start debugger before remote config to subscribe to it before starting to poll
      maybeStartDebugger(instrumentation, scoClass, sco);
      maybeStartRemoteConfig(scoClass, sco);
      maybeStartAiGuard();

      if (telemetryEnabled) {
        startTelemetry(instrumentation, scoClass, sco);
      }
      if (flareEnabled) {
        startFlarePoller(scoClass, sco);
      }
    }

    private void resumeRemoteComponents() {
      log.debug("Resuming remote components.");
      try {
        // remote components were paused for custom log-manager/jmx-builder
        // add small delay before resuming remote I/O to help stabilization
        Thread.sleep(okHttpDelayMillis);
        scoClass.getMethod("resume").invoke(sco);
      } catch (InterruptedException ignore) {
      } catch (Throwable e) {
        log.error("Error resuming remote components", e);
      }
    }
  }

  protected static class StartProfilingAgentCallback extends ClassLoadCallBack {
    private final Instrumentation inst;

    protected StartProfilingAgentCallback(Instrumentation inst) {
      this.inst = inst;
    }

    @Override
    public AgentThread agentThread() {
      return PROFILER_STARTUP;
    }

    @Override
    public void execute() {
      startProfilingAgent(false, true, inst);
      // only enable instrumentation based profilers when we know JFR is ready
      InstrumentationBasedProfiling.enableInstrumentationBasedProfiling();
    }
  }

  private static synchronized void createAgentClassloader(final URL agentJarURL) {
    if (AGENT_CLASSLOADER == null) {
      try {
        BootstrapProxy.addBootstrapResource(agentJarURL);

        // assume this is the right location of other agent-bootstrap classes
        ClassLoader parent = Agent.class.getClassLoader();
        if (parent == null && isJavaVersionAtLeast(9)) {
          // for Java9+ replace any JDK bootstrap reference with platform loader
          parent = getPlatformClassLoader();
        }

        AGENT_CLASSLOADER = new DatadogClassLoader(agentJarURL, parent);
      } catch (final Throwable ex) {
        log.error("Throwable thrown creating agent classloader", ex);
      }
    }
  }

  private static void maybeStartRemoteConfig(Class<?> scoClass, Object sco) {
    if (!remoteConfigEnabled) {
      return;
    }

    StaticEventLogger.begin("Remote Config");

    try {
      Method pollerMethod = scoClass.getMethod("configurationPoller", Config.class);
      Object poller = pollerMethod.invoke(sco, Config.get());
      if (poller == null) {
        log.debug("Remote config is not enabled");
        StaticEventLogger.end("Remote Config");
        return;
      }
      Class<?> pollerCls = AGENT_CLASSLOADER.loadClass("datadog.remoteconfig.ConfigurationPoller");
      Method startMethod = pollerCls.getMethod("start");
      log.debug("Starting remote config poller");
      startMethod.invoke(poller);
    } catch (Exception e) {
      log.error("Error starting remote config", e);
    }

    StaticEventLogger.end("Remote Config");
  }

  private static synchronized void startDatadogAgent(
      final InitializationTelemetry initTelemetry, final Instrumentation inst) {
    if (null != inst) {
      StaticEventLogger.begin("BytebuddyAgent");

      try {
        final Class<?> agentInstallerClass =
            AGENT_CLASSLOADER.loadClass(AGENT_INSTALLER_CLASS_NAME);
        final Method agentInstallerMethod =
            agentInstallerClass.getMethod("installBytebuddyAgent", Instrumentation.class);
        agentInstallerMethod.invoke(null, inst);
      } catch (final Throwable ex) {
        log.error("Throwable thrown while installing the Datadog Agent", ex);
        initTelemetry.onFatalError(ex);
      } finally {
        StaticEventLogger.end("BytebuddyAgent");
      }
    }
  }

  private static synchronized void installDatadogTracer(
      InitializationTelemetry initTelemetry, Class<?> scoClass, Object sco) {
    if (AGENT_CLASSLOADER == null) {
      throw new IllegalStateException("Datadog agent should have been started already");
    }

    StaticEventLogger.begin("GlobalTracer");

    // TracerInstaller.installGlobalTracer can be called multiple times without any problem
    // so there is no need to have a 'datadogTracerInstalled' flag here.
    try {
      // install global tracer
      final Class<?> tracerInstallerClass =
          AGENT_CLASSLOADER.loadClass("datadog.trace.agent.tooling.TracerInstaller");
      final Method tracerInstallerMethod =
          tracerInstallerClass.getMethod(
              "installGlobalTracer", scoClass, ProfilingContextIntegration.class);
      tracerInstallerMethod.invoke(null, sco, createProfilingContextIntegration());
    } catch (final FatalAgentMisconfigurationError ex) {
      throw ex;
    } catch (final Throwable ex) {
      log.error("Throwable thrown while installing the Datadog Tracer", ex);

      initTelemetry.onFatalError(ex);
    }

    StaticEventLogger.end("GlobalTracer");
  }

  private static void startCrashTracking() {
    if (isJavaVersionAtLeast(9)) {
      // it is safe to initialize crashtracking early
      // since it can take 100ms+ to initialize the native library we will defer the initialization
      // ... unless we request early start with the debug config flag
      boolean forceEarlyStart = CrashTrackingConfig.CRASH_TRACKING_START_EARLY_DEFAULT;
      String forceEarlyStartStr =
          ddGetProperty("dd." + CrashTrackingConfig.CRASH_TRACKING_START_EARLY);
      if (forceEarlyStartStr != null) {
        forceEarlyStart = Boolean.parseBoolean(forceEarlyStartStr);
      }
      if (forceEarlyStart) {
        initializeCrashTrackingDefault();
      } else {
        AgentTaskScheduler.get().execute(Agent::initializeCrashTrackingDefault);
      }
    } else {
      // for Java 8 we are relying on JMX to give us the process PID
      // we need to delay the crash tracking initialization until JMX is available
      CRASHTRACKER_INIT_AFTER_JMX = Agent::initializeDelayedCrashTracking;
    }
  }

  private static void scheduleJmxStart(final int jmxStartDelay) {
    if (jmxStartDelay > 0) {
      AgentTaskScheduler.get()
          .scheduleWithJitter(new JmxStartTask(), jmxStartDelay, TimeUnit.SECONDS);
    } else {
      startJmx();
    }
  }

  static final class JmxStartTask implements Runnable {
    @Override
    public void run() {
      startJmx();
    }
  }

  private static synchronized void startJmx() {
    if (AGENT_CLASSLOADER == null) {
      throw new IllegalStateException("Datadog agent should have been started already");
    }
    if (jmxStarting.getAndSet(true)) {
      return; // another thread is already in startJmx
    }
    if (jmxFetchEnabled) {
      startJmxFetch();
    }
    initializeJmxSystemAccessProvider(AGENT_CLASSLOADER);
    if (crashTrackingEnabled && CRASHTRACKER_INIT_AFTER_JMX != null) {
      try {
        CRASHTRACKER_INIT_AFTER_JMX.run();
      } finally {
        CRASHTRACKER_INIT_AFTER_JMX = null;
      }
    }
    if (profilingEnabled) {
      registerDeadlockDetectionEvent();
      registerSmapEntryEvent();
      if (PROFILER_INIT_AFTER_JMX != null) {
        try {
          /*
          When getJmxStartDelay() is set to 0 we will attempt to initialize the JMX subsystem as soon as available.
          But, this can cause issues with JFR as it needs some 'grace period' after JMX is ready. That's why we are
          re-scheduling the profiler initialization code just a tad later.

          If the jmx start delay is set, we are already delayed relative to the jmx init so we can just plainly
          run the initialization code.
          */
          if (getJmxStartDelay() == 0) {
            log.debug("Waiting for profiler initialization");
            AgentTaskScheduler.get()
                .scheduleWithJitter(PROFILER_INIT_AFTER_JMX, 500, TimeUnit.MILLISECONDS);
          } else {
            log.debug("Initializing profiler");
            PROFILER_INIT_AFTER_JMX.run();
          }
        } finally {
          PROFILER_INIT_AFTER_JMX = null;
        }
      }
    }
  }

  private static synchronized void registerDeadlockDetectionEvent() {
    log.debug("Initializing JMX thread deadlock detector");
    try {
      final Class<?> deadlockFactoryClass =
          AGENT_CLASSLOADER.loadClass(
              "com.datadog.profiling.controller.openjdk.events.DeadlockEventFactory");
      final Method registerMethod = deadlockFactoryClass.getMethod("registerEvents");
      registerMethod.invoke(null);
    } catch (final NoClassDefFoundError
        | ClassNotFoundException
        | UnsupportedClassVersionError ignored) {
      log.debug("JMX deadlock detection not supported");
    } catch (final Throwable ex) {
      log.error("Unable to initialize JMX thread deadlock detector", ex);
    }
  }

  private static synchronized void registerSmapEntryEvent() {
    log.debug("Initializing smap entry scraping");
    try {
      final Class<?> smapFactoryClass =
          AGENT_CLASSLOADER.loadClass(
              "com.datadog.profiling.controller.openjdk.events.SmapEntryFactory");
      final Method registerMethod = smapFactoryClass.getMethod("registerEvents");
      registerMethod.invoke(null);
    } catch (final Exception ignored) {
      log.debug("Smap entry scraping not supported");
    }
  }

  /** Enable JMX based system access provider once it is safe to touch JMX */
  private static synchronized void initializeJmxSystemAccessProvider(
      final ClassLoader classLoader) {
    if (log.isDebugEnabled()) {
      log.debug("Initializing JMX system access provider for {}", classLoader);
    }
    try {
      final Class<?> tracerInstallerClass =
          classLoader.loadClass("datadog.trace.core.util.SystemAccess");
      final Method enableJmxMethod = tracerInstallerClass.getMethod("enableJmx");
      enableJmxMethod.invoke(null);
    } catch (final Throwable ex) {
      log.error("Throwable thrown while initializing JMX system access provider", ex);
    }
  }

  private static synchronized void startJmxFetch() {
    final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(AGENT_CLASSLOADER);
      final Class<?> jmxFetchAgentClass =
          AGENT_CLASSLOADER.loadClass("datadog.trace.agent.jmxfetch.JMXFetch");
      final Method jmxFetchInstallerMethod =
          jmxFetchAgentClass.getMethod("run", StatsDClientManager.class);
      jmxFetchInstallerMethod.invoke(null, statsDClientManager());
    } catch (final Throwable ex) {
      log.error("Throwable thrown while starting JmxFetch", ex);
    } finally {
      safelySetContextClassLoader(contextLoader);
    }
  }

  private static StatsDClientManager statsDClientManager() throws Exception {
    final Class<?> statsdClientManagerClass =
        AGENT_CLASSLOADER.loadClass("datadog.communication.monitor.DDAgentStatsDClientManager");
    final Method statsDClientManagerMethod =
        statsdClientManagerClass.getMethod("statsDClientManager");
    return (StatsDClientManager) statsDClientManagerMethod.invoke(null);
  }

  private static void maybeStartAiGuard() {
    if (!Config.get().isAiGuardEnabled()) {
      return;
    }
    try {
      final Class<?> aiGuardSystemClass =
          AGENT_CLASSLOADER.loadClass("com.datadog.aiguard.AIGuardSystem");
      final Method aiGuardInstallerMethod = aiGuardSystemClass.getMethod("start");
      aiGuardInstallerMethod.invoke(null);
    } catch (final Exception e) {
      log.debug("Error initializing AI Guard", e);
    }
  }

  private static void maybeStartAppSec(Class<?> scoClass, Object o) {

    try {
      // event tracking SDK must be available for customers even if AppSec is fully disabled
      AppSecEventTracker.install();
    } catch (final Exception e) {
      log.debug("Error starting AppSec Event Tracker", e);
    }

    if (!(appSecEnabled || (remoteConfigEnabled && !appSecFullyDisabled))) {
      return;
    }

    StaticEventLogger.begin("AppSec");

    try {
      SubscriptionService ss = AgentTracer.get().getSubscriptionService(RequestContextSlot.APPSEC);
      startAppSec(ss, scoClass, o);
    } catch (Exception e) {
      log.error("Error starting AppSec System", e);
    }

    StaticEventLogger.end("AppSec");
  }

  private static void startAppSec(SubscriptionService ss, Class<?> scoClass, Object sco) {
    try {
      final Class<?> appSecSysClass =
          AGENT_CLASSLOADER.loadClass("com.datadog.appsec.AppSecSystem");
      final Method appSecInstallerMethod =
          appSecSysClass.getMethod("start", SubscriptionService.class, scoClass);
      appSecInstallerMethod.invoke(null, ss, sco);
    } catch (final Throwable ex) {
      log.warn("Not starting AppSec subsystem: {}", ex.getMessage());
    }
  }

  private static boolean isSupportedAppSecArch() {
    final String arch = SystemProperties.get("os.arch");
    if (OperatingSystem.isWindows()) {
      // TODO: Windows bindings need to be built for x86
      return "amd64".equals(arch) || "x86_64".equals(arch);
    } else if (OperatingSystem.isMacOs()) {
      return "amd64".equals(arch) || "x86_64".equals(arch) || "aarch64".equals(arch);
    } else if (OperatingSystem.isLinux()) {
      return "amd64".equals(arch) || "x86_64".equals(arch) || "aarch64".equals(arch);
    }
    // Still return true in other if unexpected cases (e.g. SunOS), and we'll handle loading errors
    // during AppSec startup.
    return true;
  }

  private static void maybeStartIast(Instrumentation instrumentation) {
    if (iastEnabled || !iastFullyDisabled) {

      StaticEventLogger.begin("IAST");

      try {
        SubscriptionService ss = AgentTracer.get().getSubscriptionService(RequestContextSlot.IAST);
        startIast(instrumentation, ss);
      } catch (Exception e) {
        log.error("Error starting IAST subsystem", e);
      }

      StaticEventLogger.end("IAST");
    }
  }

  private static void startIast(Instrumentation instrumentation, SubscriptionService ss) {
    try {
      final Class<?> appSecSysClass = AGENT_CLASSLOADER.loadClass("com.datadog.iast.IastSystem");
      final Method iastInstallerMethod =
          appSecSysClass.getMethod("start", Instrumentation.class, SubscriptionService.class);
      iastInstallerMethod.invoke(null, instrumentation, ss);
    } catch (final Throwable e) {
      log.warn("Not starting IAST subsystem", e);
    }
  }

  private static void maybeStartCiVisibility(Instrumentation inst, Class<?> scoClass, Object sco) {
    if (ciVisibilityEnabled) {
      StaticEventLogger.begin("CI Visibility");

      try {
        final Class<?> ciVisibilitySysClass =
            AGENT_CLASSLOADER.loadClass("datadog.trace.civisibility.CiVisibilitySystem");
        final Method ciVisibilityInstallerMethod =
            ciVisibilitySysClass.getMethod("start", Instrumentation.class, scoClass);
        ciVisibilityInstallerMethod.invoke(null, inst, sco);
      } catch (final Throwable e) {
        log.warn("Not starting CI Visibility subsystem", e);
      }

      StaticEventLogger.end("CI Visibility");
    }
  }

  private static void maybeStartLLMObs(Instrumentation inst, Class<?> scoClass, Object sco) {
    if (llmObsEnabled) {
      StaticEventLogger.begin("LLM Observability");

      try {
        final Class<?> llmObsSysClass =
            AGENT_CLASSLOADER.loadClass("datadog.trace.llmobs.LLMObsSystem");
        final Method llmObsInstallerMethod =
            llmObsSysClass.getMethod("start", Instrumentation.class, scoClass);
        llmObsInstallerMethod.invoke(null, inst, sco);
      } catch (final Throwable e) {
        log.warn("Not starting LLM Observability subsystem", e);
      }

      StaticEventLogger.end("LLM Observability");
    }
  }

  private static void maybeInstallLogsIntake(Class<?> scoClass, Object sco) {
    if (agentlessLogSubmissionEnabled) {
      StaticEventLogger.begin("Logs Intake");

      try {
        final Class<?> logsIntakeSystemClass =
            AGENT_CLASSLOADER.loadClass("datadog.trace.logging.intake.LogsIntakeSystem");
        final Method logsIntakeInstallerMethod =
            logsIntakeSystemClass.getMethod("install", scoClass);
        logsIntakeInstallerMethod.invoke(null, sco);
      } catch (final Throwable e) {
        log.warn("Not installing Logs Intake subsystem", e);
      }

      StaticEventLogger.end("Logs Intake");
    }
  }

  private static void shutdownLogsIntake() {
    if (AGENT_CLASSLOADER == null) {
      // It wasn't started, so no need to shut it down
      return;
    }
    try {
      Thread.currentThread().setContextClassLoader(AGENT_CLASSLOADER);
      final Class<?> logsIntakeSystemClass =
          AGENT_CLASSLOADER.loadClass("datadog.trace.logging.intake.LogsIntakeSystem");
      final Method shutdownMethod = logsIntakeSystemClass.getMethod("shutdown");
      shutdownMethod.invoke(null);
    } catch (final Throwable ex) {
      log.error("Throwable thrown while shutting down logs intake", ex);
    }
  }

  private static void startTelemetry(Instrumentation inst, Class<?> scoClass, Object sco) {
    StaticEventLogger.begin("Telemetry");

    try {
      final Class<?> telemetrySystem =
          AGENT_CLASSLOADER.loadClass("datadog.telemetry.TelemetrySystem");
      final Method startTelemetry =
          telemetrySystem.getMethod("startTelemetry", Instrumentation.class, scoClass);
      startTelemetry.invoke(null, inst, sco);
    } catch (final Throwable ex) {
      log.warn("Unable start telemetry", ex);
    }

    StaticEventLogger.end("Telemetry");
  }

  private static void stopTelemetry() {
    if (AGENT_CLASSLOADER == null) {
      return;
    }

    try {
      final Class<?> telemetrySystem =
          AGENT_CLASSLOADER.loadClass("datadog.telemetry.TelemetrySystem");
      final Method stopTelemetry = telemetrySystem.getMethod("stop");
      stopTelemetry.invoke(null);
    } catch (final Throwable ex) {
      log.error("Error encountered while stopping telemetry", ex);
    }
  }

  private static void startFlarePoller(Class<?> scoClass, Object sco) {
    StaticEventLogger.begin("Flare Poller");
    try {
      final Class<?> tracerFlarePollerClass =
          AGENT_CLASSLOADER.loadClass("datadog.flare.TracerFlarePoller");
      final Method tracerFlarePollerStartMethod =
          tracerFlarePollerClass.getMethod("start", scoClass);
      tracerFlarePollerStartMethod.invoke(null, sco);
    } catch (final Throwable e) {
      log.warn("Unable start Flare Poller", e);
    }
    StaticEventLogger.end("Flare Poller");
  }

  private static void stopFlarePoller() {
    if (AGENT_CLASSLOADER == null) {
      return;
    }
    try {
      final Class<?> tracerFlarePollerClass =
          AGENT_CLASSLOADER.loadClass("datadog.flare.TracerFlarePoller");
      final Method tracerFlarePollerStopMethod = tracerFlarePollerClass.getMethod("stop");
      tracerFlarePollerStopMethod.invoke(null);
    } catch (final Throwable ex) {
      log.warn("Error encountered while stopping Flare Poller", ex);
    }
  }

  private static void initializeDelayedCrashTracking() {
    initializeCrashTracking(true, isCrashTrackingAutoconfigEnabled());
  }

  private static void initializeDelayedCrashTrackingOnlyJmx() {
    initializeCrashTracking(true, false);
  }

  private static void initializeCrashTrackingDefault() {
    initializeCrashTracking(false, isCrashTrackingAutoconfigEnabled());
  }

  private static boolean isCrashTrackingAutoconfigEnabled() {
    String enabledVal = ddGetProperty("dd." + CrashTrackingConfig.CRASH_TRACKING_ENABLE_AUTOCONFIG);
    boolean enabled = CrashTrackingConfig.CRASH_TRACKING_ENABLE_AUTOCONFIG_DEFAULT;
    if (enabledVal != null) {
      enabled = Boolean.parseBoolean(enabledVal);
    } else {
      // If the property is not set, then we check if profiling is enabled
      enabled = profilingEnabled;
    }
    return enabled;
  }

  private static void initializeCrashTracking(boolean delayed, boolean checkNative) {
    if (JavaVirtualMachine.isJ9()) {
      // TODO currently crash tracking is supported only for HotSpot based JVMs
      return;
    }
    log.debug("Initializing crashtracking");
    try {
      Class<?> clz = AGENT_CLASSLOADER.loadClass("datadog.crashtracking.Initializer");
      // first try to use the JVMAccess using the native library; unless `checkNative` is false
      Boolean rslt =
          checkNative && (Boolean) clz.getMethod("initialize", boolean.class).invoke(null, false);
      if (!rslt) {
        if (delayed) {
          // already delayed initialization, so no need to reschedule it again
          // just call initialize and force JMX
          rslt = (Boolean) clz.getMethod("initialize", boolean.class).invoke(null, true);
        } else {
          // delayed initialization, so we need to reschedule it and mark as delayed but do not
          // re-check the native library
          CRASHTRACKER_INIT_AFTER_JMX = Agent::initializeDelayedCrashTrackingOnlyJmx;
          rslt = null; // we will initialize it later
        }
      }
      if (rslt == null) {
        log.debug("Crashtracking initialization delayed until JMX is available");
      } else if (rslt) {
        log.debug("Crashtracking initialized");
      } else {
        log.debug(
            SEND_TELEMETRY, "Crashtracking failed to initialize. No additional details available.");
      }
    } catch (Throwable t) {
      log.debug(SEND_TELEMETRY, "Unable to initialize crashtracking");
    }
  }

  private static void startCwsAgent() {
    if (AGENT_CLASSLOADER.getResource("cws-tls.version") == null) {
      log.warn("CWS support not included in this build of `dd-java-agent`");
      return;
    }
    log.debug("Scheduling scope event factory registration");
    WithGlobalTracer.registerOrExecute(
        new WithGlobalTracer.Callback() {
          @Override
          public void withTracer(TracerAPI tracer) {
            log.debug("Registering CWS scope tracker");
            try {
              ScopeListener scopeListener =
                  (ScopeListener)
                      AGENT_CLASSLOADER
                          .loadClass("datadog.cws.tls.TlsScopeListener")
                          .getDeclaredConstructor()
                          .newInstance();
              tracer.addScopeListener(scopeListener);
              log.debug("Scope event factory {} has been registered", scopeListener);
            } catch (Throwable e) {
              if (e instanceof InvocationTargetException) {
                e = e.getCause();
              }
              log.debug("CWS is not available. {}", e.getMessage());
            }
          }
        });
  }

  /**
   * {@see com.datadog.profiling.ddprof.DatadogProfilingIntegration} must not be modified to depend
   * on JFR.
   */
  private static ProfilingContextIntegration createProfilingContextIntegration() {
    if (Config.get().isProfilingEnabled()) {
      if (Config.get().isDatadogProfilerEnabled() && !OperatingSystem.isWindows()) {
        try {
          return (ProfilingContextIntegration)
              AGENT_CLASSLOADER
                  .loadClass("com.datadog.profiling.ddprof.DatadogProfilingIntegration")
                  .getDeclaredConstructor()
                  .newInstance();
        } catch (Throwable t) {
          log.debug("ddprof-based profiling context labeling not available. {}", t.getMessage());
        }
      }
      if (Config.get().isProfilingTimelineEventsEnabled()) {
        // important: note that this will not initialise JFR until onStart is called
        try {
          return (ProfilingContextIntegration)
              AGENT_CLASSLOADER
                  .loadClass("com.datadog.profiling.controller.openjdk.JFREventContextIntegration")
                  .getDeclaredConstructor()
                  .newInstance();
        } catch (Throwable t) {
          log.debug("JFR event-based profiling context labeling not available. {}", t.getMessage());
        }
      }
    }
    return ProfilingContextIntegration.NoOp.INSTANCE;
  }

  private static boolean startProfilingAgent(
      final boolean earlyStart, final boolean firstAttempt, Instrumentation inst) {
    if (isAwsLambdaRuntime()) {
      if (firstAttempt) {
        log.info("Profiling not supported in AWS Lambda runtimes");
      }
      return false;
    }

    boolean requestRetry = false;

    if (firstAttempt) {
      StaticEventLogger.begin("ProfilingAgent");
      final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
      try {
        Thread.currentThread().setContextClassLoader(AGENT_CLASSLOADER);
        final Class<?> profilingAgentClass =
            AGENT_CLASSLOADER.loadClass("com.datadog.profiling.agent.ProfilingAgent");
        final Method profilingInstallerMethod =
            profilingAgentClass.getMethod("run", Boolean.TYPE, Instrumentation.class);
        requestRetry = (boolean) profilingInstallerMethod.invoke(null, earlyStart, inst);
      } catch (final Throwable ex) {
        log.error(SEND_TELEMETRY, "Throwable thrown while starting profiling agent", ex);
      } finally {
        safelySetContextClassLoader(contextLoader);
      }
      StaticEventLogger.end("ProfilingAgent");
    }
    if (!earlyStart) {
      /*
       * Install the tracer hooks only when not using 'early start'.
       * The 'early start' is happening so early that most of the infrastructure has not been set up yet.
       */
      initProfilerContext();
    }
    return requestRetry;
  }

  private static void initProfilerContext() {
    log.debug("Scheduling profiler context initialization");
    WithGlobalTracer.registerOrExecute(
        tracer -> {
          log.debug("Initializing profiler context integration");
          tracer.getProfilingContext().onStart();
        });
  }

  private static boolean isAwsLambdaRuntime() {
    return !EnvironmentVariables.getOrDefault("AWS_LAMBDA_FUNCTION_NAME", "").isEmpty();
  }

  private static void shutdownProfilingAgent(final boolean sync) {
    if (AGENT_CLASSLOADER == null) {
      // It wasn't started, so no need to shut it down
      return;
    }

    final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(AGENT_CLASSLOADER);
      final Class<?> profilingAgentClass =
          AGENT_CLASSLOADER.loadClass("com.datadog.profiling.agent.ProfilingAgent");
      final Method profilingInstallerMethod =
          profilingAgentClass.getMethod("shutdown", Boolean.TYPE);
      profilingInstallerMethod.invoke(null, sync);
    } catch (final Throwable ex) {
      log.error("Throwable thrown while shutting down profiling agent", ex);
    } finally {
      safelySetContextClassLoader(contextLoader);
    }
  }

  private static void maybeStartDebugger(Instrumentation inst, Class<?> scoClass, Object sco) {
    if (isExplicitlyDisabled(DebuggerConfig.DYNAMIC_INSTRUMENTATION_ENABLED)
        && isExplicitlyDisabled(DebuggerConfig.EXCEPTION_REPLAY_ENABLED)
        && isExplicitlyDisabled(TraceInstrumentationConfig.CODE_ORIGIN_FOR_SPANS_ENABLED)
        && isExplicitlyDisabled(DebuggerConfig.DISTRIBUTED_DEBUGGER_ENABLED)) {
      return;
    }
    startDebuggerAgent(inst, scoClass, sco);
  }

  private static boolean isExplicitlyDisabled(String booleanKey) {
    return Config.get().configProvider().isSet(booleanKey)
        && !Config.get().configProvider().getBoolean(booleanKey);
  }

  private static synchronized void startDebuggerAgent(
      Instrumentation inst, Class<?> scoClass, Object sco) {
    StaticEventLogger.begin("Debugger");

    final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(AGENT_CLASSLOADER);
      final Class<?> debuggerAgentClass =
          AGENT_CLASSLOADER.loadClass("com.datadog.debugger.agent.DebuggerAgent");
      final Method debuggerInstallerMethod =
          debuggerAgentClass.getMethod("run", Instrumentation.class, scoClass);
      debuggerInstallerMethod.invoke(null, inst, sco);
    } catch (final Throwable ex) {
      log.error("Throwable thrown while starting debugger agent", ex);
    } finally {
      safelySetContextClassLoader(contextLoader);
    }

    StaticEventLogger.end("Debugger");
  }

  private static void configureLogger() {
    setSystemPropertyDefault(SIMPLE_LOGGER_SHOW_DATE_TIME_PROPERTY, "true");
    setSystemPropertyDefault(SIMPLE_LOGGER_JSON_ENABLED_PROPERTY, "false");
    String simpleLoggerJsonEnabled = SystemProperties.get(SIMPLE_LOGGER_JSON_ENABLED_PROPERTY);
    if (simpleLoggerJsonEnabled != null && simpleLoggerJsonEnabled.equalsIgnoreCase("true")) {
      setSystemPropertyDefault(
          SIMPLE_LOGGER_DATE_TIME_FORMAT_PROPERTY, SIMPLE_LOGGER_DATE_TIME_FORMAT_JSON_DEFAULT);
    } else {
      setSystemPropertyDefault(
          SIMPLE_LOGGER_DATE_TIME_FORMAT_PROPERTY, SIMPLE_LOGGER_DATE_TIME_FORMAT_DEFAULT);
    }

    String logLevel;
    if (isDebugMode()) {
      logLevel = "DEBUG";
    } else {
      logLevel = ddGetProperty("dd.log.level");
      if (null == logLevel) {
        logLevel = EnvironmentVariables.get("OTEL_LOG_LEVEL");
      }
    }

    if (null == logLevel && !isFeatureEnabled(AgentFeature.STARTUP_LOGS)) {
      logLevel = "WARN";
    }

    if (null != logLevel) {
      setSystemPropertyDefault(SIMPLE_LOGGER_DEFAULT_LOG_LEVEL_PROPERTY, logLevel);
    }
  }

  private static void setSystemPropertyDefault(final String property, final String value) {
    if (SystemProperties.get(property) == null && ddGetEnv(property) == null) {
      SystemProperties.set(property, value);
    }
  }

  private static ClassLoader getPlatformClassLoader()
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    /*
     Must invoke ClassLoader.getPlatformClassLoader by reflection to remain
     compatible with java 7 + 8.
    */
    final Method method = ClassLoader.class.getDeclaredMethod("getPlatformClassLoader");
    return (ClassLoader) method.invoke(null);
  }

  /**
   * Determine if we should log in debug level according to dd.trace.debug
   *
   * @return true if we should
   */
  private static boolean isDebugMode() {
    final String tracerDebugLevelSysprop = "dd.trace.debug";
    final String tracerDebugLevelProp = SystemProperties.get(tracerDebugLevelSysprop);

    if (tracerDebugLevelProp != null) {
      return Boolean.parseBoolean(tracerDebugLevelProp);
    }

    final String tracerDebugLevelEnv = ddGetEnv(tracerDebugLevelSysprop);

    if (tracerDebugLevelEnv != null) {
      return Boolean.parseBoolean(tracerDebugLevelEnv);
    }
    return false;
  }

  /**
   * @return {@code true} if the agent feature is enabled
   */
  private static boolean isFeatureEnabled(AgentFeature feature) {
    // must be kept in sync with logic from Config!
    final String featureConfigKey = feature.getConfigKey();
    final String featureSystemProp = feature.getSystemProp();
    String featureEnabled = SystemProperties.get(featureSystemProp);
    if (featureEnabled == null) {
      featureEnabled = getStableConfig(FLEET, featureConfigKey);
    }
    if (featureEnabled == null) {
      featureEnabled = ddGetEnv(featureSystemProp);
    }
    if (featureEnabled == null) {
      featureEnabled = getStableConfig(LOCAL, featureConfigKey);
    }

    if (feature.isEnabledByDefault()) {
      // true unless it's explicitly set to "false"
      return !("false".equalsIgnoreCase(featureEnabled) || "0".equals(featureEnabled));
    } else {
      if (feature == AgentFeature.PROFILING) {
        // We need this hack because profiling in SSI can receive 'auto' value in
        // the enablement config
        return ProfilingEnablement.of(featureEnabled).isActive();
      }
      // false unless it's explicitly set to "true"
      return Boolean.parseBoolean(featureEnabled) || "1".equals(featureEnabled);
    }
  }

  /**
   * @see datadog.trace.api.ProductActivation#fromString(String)
   */
  private static boolean isFullyDisabled(final AgentFeature feature) {
    // must be kept in sync with logic from Config!
    final String featureConfigKey = feature.getConfigKey();
    final String featureSystemProp = feature.getSystemProp();
    String settingValue = getNullIfEmpty(SystemProperties.get(featureSystemProp));
    if (settingValue == null) {
      settingValue = getNullIfEmpty(getStableConfig(FLEET, featureConfigKey));
    }
    if (settingValue == null) {
      settingValue = getNullIfEmpty(ddGetEnv(featureSystemProp));
    }
    if (settingValue == null) {
      settingValue = getNullIfEmpty(getStableConfig(LOCAL, featureConfigKey));
    }

    // defaults to inactive
    return !(settingValue == null
        || settingValue.equalsIgnoreCase("true")
        || settingValue.equalsIgnoreCase("1")
        || settingValue.equalsIgnoreCase("inactive"));
  }

  /** IAST will be enabled in opt-out if it's not actively disabled and AppSec is enabled */
  private static boolean isIastFullyDisabled(final boolean isAppSecEnabled) {
    if (isFullyDisabled(AgentFeature.IAST)) {
      return true;
    }
    return !isAppSecEnabled;
  }

  private static String getNullIfEmpty(final String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    return value;
  }

  /**
   * @return configured JMX start delay in seconds
   */
  private static int getJmxStartDelay() {
    String startDelay = ddGetProperty("dd.dogstatsd.start-delay");
    if (startDelay == null) {
      startDelay = ddGetProperty("dd.jmxfetch.start-delay");
    }
    if (startDelay != null) {
      try {
        return Integer.parseInt(startDelay);
      } catch (NumberFormatException e) {
        // fall back to default delay
      }
    }
    return DEFAULT_JMX_START_DELAY;
  }

  /**
   * Search for java or datadog-tracer sysprops which indicate that a custom log manager will be
   * used. Also search for any app classes known to set a custom log manager.
   *
   * @return true if we detect a custom log manager being used.
   */
  private static boolean isAppUsingCustomLogManager(final EnumSet<Library> libraries) {
    final String tracerCustomLogManSysprop = "dd.app.customlogmanager";
    final String customLogManagerProp = SystemProperties.get(tracerCustomLogManSysprop);
    final String customLogManagerEnv = ddGetEnv(tracerCustomLogManSysprop);

    if (customLogManagerProp != null || customLogManagerEnv != null) {
      log.debug("Prop - customlogmanager: {}", customLogManagerProp);
      log.debug("Env - customlogmanager: {}", customLogManagerEnv);
      // Allow setting to skip these automatic checks:
      return Boolean.parseBoolean(customLogManagerProp)
          || Boolean.parseBoolean(customLogManagerEnv);
    }

    if (libraries.contains(WILDFLY)) {
      return true; // Wildfly is known to set a custom log manager after startup.
    }

    final String logManagerProp = SystemProperties.get("java.util.logging.manager");
    if (logManagerProp != null) {
      log.debug("Prop - logging.manager: {}", logManagerProp);
      return true;
    }

    return false;
  }

  /**
   * Search for java or datadog-tracer sysprops which indicate that a custom JMX builder will be
   * used.
   *
   * @return true if we detect a custom JMX builder being used.
   */
  private static boolean isAppUsingCustomJMXBuilder(final EnumSet<Library> libraries) {
    final String tracerCustomJMXBuilderSysprop = "dd.app.customjmxbuilder";
    final String customJMXBuilderProp = SystemProperties.get(tracerCustomJMXBuilderSysprop);
    final String customJMXBuilderEnv = ddGetEnv(tracerCustomJMXBuilderSysprop);

    if (customJMXBuilderProp != null || customJMXBuilderEnv != null) {
      log.debug("Prop - customjmxbuilder: {}", customJMXBuilderProp);
      log.debug("Env - customjmxbuilder: {}", customJMXBuilderEnv);
      // Allow setting to skip these automatic checks:
      return Boolean.parseBoolean(customJMXBuilderProp)
          || Boolean.parseBoolean(customJMXBuilderEnv);
    }

    if (libraries.contains(WILDFLY)) {
      return true; // Wildfly is known to set a custom JMX builder after startup.
    }

    final String jmxBuilderProp = SystemProperties.get("javax.management.builder.initial");
    if (jmxBuilderProp != null) {
      log.debug("Prop - javax.management.builder.initial: {}", jmxBuilderProp);
      return true;
    }

    return false;
  }

  /** Looks for the "dd." system property first then the "DD_" environment variable equivalent. */
  private static String ddGetProperty(final String sysProp) {
    String value = SystemProperties.get(sysProp);
    if (null == value) {
      value = ddGetEnv(sysProp);
    }
    return value;
  }

  /** Looks for sysProp in the Stable Configuration input */
  private static String getStableConfig(StableConfigSource source, final String sysProp) {
    return source.get(sysProp);
  }

  /** Looks for the "DD_" environment variable equivalent of the given "dd." system property. */
  private static String ddGetEnv(final String sysProp) {
    return EnvironmentVariables.get(toEnvVar(sysProp));
  }

  private static boolean okHttpMayIndirectlyLoadJUL() {
    if (isCustomSecurityProviderInstalled() || isIBMSASLInstalled()) {
      return true; // custom security providers may load JUL when OkHttp accesses TLS
    }
    if (isJavaVersionAtLeast(9)) {
      return false; // JDKs since 9 have reworked JFR to use a different logging facility, not JUL
    }
    return isJFRSupported(); // assume OkHttp will indirectly load JUL via its JFR events
  }

  private static boolean isCustomSecurityProviderInstalled() {
    return ClassLoader.getSystemResource("META-INF/services/java.security.Provider") != null;
  }

  private static boolean isIBMSASLInstalled() {
    // need explicit check as this is installed without using the service-loader mechanism
    return ClassLoader.getSystemResource("com/ibm/security/sasl/IBMSASL.class") != null;
  }

  private static boolean isJFRSupported() {
    // FIXME: this is quite a hack because there maybe jfr classes on classpath somehow that have
    // nothing to do with JDK - but this should be safe because only thing this does is to delay
    // tracer install
    return BootstrapProxy.INSTANCE.getResource("jdk/jfr/Recording.class") != null;
  }

  private static boolean isDataJobsSupported(String javaCommand, String dataJobsCommandPattern) {
    if (null == javaCommand || null == dataJobsCommandPattern) {
      // if sun.java.command somehow is not set or data jobs command pattern is not
      // set, assume it's supported due to lack of info.
      return true;
    }

    try {
      return javaCommand.matches(dataJobsCommandPattern);
    } catch (PatternSyntaxException e) {
      log.warn(
          "Invalid data jobs command pattern {}. The value must be a valid regex",
          dataJobsCommandPattern);
    }

    return true;
  }
}
