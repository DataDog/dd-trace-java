package datadog.trace.bootstrap;

import static datadog.trace.api.ConfigDefaults.DEFAULT_STARTUP_LOGS_ENABLED;
import static datadog.trace.api.Platform.getRuntimeVendor;
import static datadog.trace.api.Platform.isJavaVersionAtLeast;
import static datadog.trace.api.Platform.isOracleJDK8;
import static datadog.trace.bootstrap.Library.WILDFLY;
import static datadog.trace.bootstrap.Library.detectLibraries;
import static datadog.trace.util.AgentThreadFactory.AgentThread.JMX_STARTUP;
import static datadog.trace.util.AgentThreadFactory.AgentThread.PROFILER_STARTUP;
import static datadog.trace.util.AgentThreadFactory.AgentThread.TRACE_STARTUP;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static datadog.trace.util.Strings.getResourceName;
import static datadog.trace.util.Strings.propertyNameToSystemPropertyName;
import static datadog.trace.util.Strings.toEnvVar;

import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import datadog.trace.api.StatsDClientManager;
import datadog.trace.api.WithGlobalTracer;
import datadog.trace.api.config.AppSecConfig;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.api.config.CwsConfig;
import datadog.trace.api.config.DebuggerConfig;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.IastConfig;
import datadog.trace.api.config.JmxFetchConfig;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.config.RemoteConfigConfig;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.config.UsmConfig;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.bootstrap.benchmark.StaticEventLogger;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.AgentThreadFactory.AgentThread;
import datadog.trace.util.throwable.FatalAgentMisconfigurationError;
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
    TRACING(propertyNameToSystemPropertyName(TraceInstrumentationConfig.TRACE_ENABLED), true),
    JMXFETCH(propertyNameToSystemPropertyName(JmxFetchConfig.JMX_FETCH_ENABLED), true),
    STARTUP_LOGS(
        propertyNameToSystemPropertyName(GeneralConfig.STARTUP_LOGS_ENABLED),
        DEFAULT_STARTUP_LOGS_ENABLED),
    PROFILING(propertyNameToSystemPropertyName(ProfilingConfig.PROFILING_ENABLED), false),
    APPSEC(propertyNameToSystemPropertyName(AppSecConfig.APPSEC_ENABLED), false),
    IAST(propertyNameToSystemPropertyName(IastConfig.IAST_ENABLED), false),
    REMOTE_CONFIG(
        propertyNameToSystemPropertyName(RemoteConfigConfig.REMOTE_CONFIGURATION_ENABLED), true),
    DEPRECATED_REMOTE_CONFIG(
        propertyNameToSystemPropertyName(RemoteConfigConfig.REMOTE_CONFIG_ENABLED), true),
    CWS(propertyNameToSystemPropertyName(CwsConfig.CWS_ENABLED), false),
    CIVISIBILITY(propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_ENABLED), false),
    CIVISIBILITY_AGENTLESS(
        propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED), false),
    USM(propertyNameToSystemPropertyName(UsmConfig.USM_ENABLED), false),
    TELEMETRY(propertyNameToSystemPropertyName(GeneralConfig.TELEMETRY_ENABLED), true),
    DEBUGGER(propertyNameToSystemPropertyName(DebuggerConfig.DEBUGGER_ENABLED), false);

    private final String systemProp;
    private final boolean enabledByDefault;

    AgentFeature(final String systemProp, final boolean enabledByDefault) {
      this.systemProp = systemProp;
      this.enabledByDefault = enabledByDefault;
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

  private static boolean jmxFetchEnabled = true;
  private static boolean profilingEnabled = false;
  private static boolean appSecEnabled;
  private static boolean appSecFullyDisabled;
  private static boolean remoteConfigEnabled = true;
  private static boolean iastEnabled = false;
  private static boolean iastFullyDisabled;
  private static boolean cwsEnabled = false;
  private static boolean ciVisibilityEnabled = false;
  private static boolean usmEnabled = false;
  private static boolean telemetryEnabled = true;
  private static boolean debuggerEnabled = false;

  public static void start(final Instrumentation inst, final URL agentJarURL, String agentArgs) {
    StaticEventLogger.begin("Agent");
    StaticEventLogger.begin("Agent.start");

    createAgentClassloader(agentJarURL);

    if (Platform.isNativeImageBuilder()) {
      // these default services are not used during native-image builds
      jmxFetchEnabled = false;
      remoteConfigEnabled = false;
      telemetryEnabled = false;
      // apply trace instrumentation, but skip starting other services
      startDatadogAgent(inst);
      StaticEventLogger.end("Agent.start");
      return;
    }

    if (agentArgs != null && !agentArgs.isEmpty()) {
      injectAgentArgsConfig(agentArgs);
    }

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

    if (!isSupportedAppSecArch()) {
      log.debug(
          "OS and architecture ({}/{}) not supported by AppSec, dd.appsec.enabled will default to false",
          System.getProperty("os.name"),
          System.getProperty("os.arch"));
      setSystemPropertyDefault(AgentFeature.APPSEC.getSystemProp(), "false");
    }

    jmxFetchEnabled = isFeatureEnabled(AgentFeature.JMXFETCH);
    profilingEnabled = isFeatureEnabled(AgentFeature.PROFILING);
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
    debuggerEnabled = isFeatureEnabled(AgentFeature.DEBUGGER);

    if (profilingEnabled) {
      if (!isOracleJDK8()) {
        // Profiling agent startup code is written in a way to allow `startProfilingAgent` be called
        // multiple times
        // If early profiling is enabled then this call will start profiling.
        // If early profiling is disabled then later call will do this.
        startProfilingAgent(true, inst);
      } else {
        log.debug("Oracle JDK 8 detected. Delaying profiler initialization.");
        // Profiling can not run early on Oracle JDK 8 because it will cause JFR initialization
        // deadlock.
        // Oracle JDK 8 JFR controller requires JMX so register an 'after-jmx-initialized' callback.
        PROFILER_INIT_AFTER_JMX =
            new Runnable() {
              @Override
              public void run() {
                startProfilingAgent(false, inst);
              }
            };
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
    startDatadogAgent(inst);

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
        // one minute fail-safe in case nothing touches JMX and and callback isn't triggered
        scheduleJmxStart(60 + jmxStartDelay);
      } else if (appUsingCustomLogManager) {
        log.debug("Custom logger detected. Delaying JMXFetch initialization.");
        registerLogManagerCallback(new StartJmxCallback(jmxStartDelay));
      } else {
        scheduleJmxStart(jmxStartDelay);
      }
    }

    boolean delayOkHttp = appUsingCustomLogManager && okHttpMayIndirectlyLoadJUL();

    /*
     * Similar thing happens with DatadogTracer on (at least) zulu-8 because it uses OkHttp which indirectly loads JFR
     * events which in turn loads LogManager. This is not a problem on newer JDKs because there JFR uses different
     * logging facility. Likewise on IBM JDKs OkHttp may indirectly load 'IBMSASL' which in turn loads LogManager.
     */
    InstallDatadogTracerCallback installDatadogTracerCallback =
        new InstallDatadogTracerCallback(inst);
    if (delayOkHttp) {
      log.debug("Custom logger detected. Delaying Datadog Tracer initialization.");
      registerLogManagerCallback(installDatadogTracerCallback);
    } else {
      installDatadogTracerCallback.execute();
    }

    /*
     * Similar thing happens with Profiler on zulu-8 because it is using OkHttp which indirectly loads JFR events which
     * in turn loads LogManager. This is not a problem on newer JDKs because there JFR uses different logging facility.
     */
    if (profilingEnabled && !isOracleJDK8()) {
      StaticEventLogger.begin("Profiling");

      if (delayOkHttp) {
        log.debug("Custom logger detected. Delaying Profiling initialization.");
        registerLogManagerCallback(new StartProfilingAgentCallback(inst));
      } else {
        startProfilingAgent(false, inst);
        // only enable instrumentation based profilers when we know JFR is ready
        InstrumentationBasedProfiling.enableInstrumentationBasedProfiling();
      }

      StaticEventLogger.end("Profiling");
    }

    StaticEventLogger.end("Agent.start");
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

  public static void shutdown(final boolean sync) {
    StaticEventLogger.end("Agent");
    StaticEventLogger.stop();

    if (profilingEnabled) {
      shutdownProfilingAgent(sync);
    }
    if (telemetryEnabled) {
      stopTelemetry();
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
  public static void startDatadogTracer() throws Exception {
    Class<?> scoClass =
        AGENT_CLASSLOADER.loadClass("datadog.communication.ddagent.SharedCommunicationObjects");
    installDatadogTracer(scoClass, scoClass.getConstructor().newInstance());
    startJmx(); // send runtime metrics along with the traces
  }

  private static void registerLogManagerCallback(final ClassLoadCallBack callback) {
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
    @Override
    public void run() {
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

    public InstallDatadogTracerCallback(Instrumentation instrumentation) {
      this.instrumentation = instrumentation;
    }

    @Override
    public AgentThread agentThread() {
      return TRACE_STARTUP;
    }

    @Override
    public void execute() {
      Object sco;
      Class<?> scoClass;
      try {
        scoClass =
            AGENT_CLASSLOADER.loadClass("datadog.communication.ddagent.SharedCommunicationObjects");
        sco = scoClass.getConstructor().newInstance();
      } catch (ClassNotFoundException
          | NoSuchMethodException
          | InstantiationException
          | IllegalAccessException
          | InvocationTargetException e) {
        throw new UndeclaredThrowableException(e);
      }

      installDatadogTracer(scoClass, sco);
      maybeStartAppSec(scoClass, sco);
      maybeStartIast(scoClass, sco);
      maybeStartCiVisibility(instrumentation, scoClass, sco);
      // start debugger before remote config to subscribe to it before starting to poll
      maybeStartDebugger(instrumentation, scoClass, sco);
      maybeStartRemoteConfig(scoClass, sco);

      if (telemetryEnabled) {
        startTelemetry(instrumentation, scoClass, sco);
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
      startProfilingAgent(false, inst);
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

  private static synchronized void startDatadogAgent(final Instrumentation inst) {
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
      }

      StaticEventLogger.end("BytebuddyAgent");
    }
  }

  private static synchronized void installDatadogTracer(Class<?> scoClass, Object sco) {
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
    }

    StaticEventLogger.end("GlobalTracer");
  }

  private static void scheduleJmxStart(final int jmxStartDelay) {
    if (jmxStartDelay > 0) {
      AgentTaskScheduler.INSTANCE.scheduleWithJitter(
          new JmxStartTask(), jmxStartDelay, TimeUnit.SECONDS);
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
    // crash uploader initialization relies on JMX being available
    initializeCrashUploader();
    if (jmxFetchEnabled) {
      startJmxFetch();
    }

    // start the jfr metrics after JMX, just to be sure
    startJfrMetrics();

    initializeJmxSystemAccessProvider(AGENT_CLASSLOADER);
    if (profilingEnabled) {
      registerDeadlockDetectionEvent();
      if (PROFILER_INIT_AFTER_JMX != null) {
        if (getJmxStartDelay() == 0) {
          log.debug("Waiting for profiler initialization");
          AgentTaskScheduler.INSTANCE.scheduleWithJitter(
              PROFILER_INIT_AFTER_JMX, 500, TimeUnit.MILLISECONDS);
        } else {
          log.debug("Initializing profiler");
          PROFILER_INIT_AFTER_JMX.run();
        }
        PROFILER_INIT_AFTER_JMX = null;
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
      Thread.currentThread().setContextClassLoader(contextLoader);
    }
  }

  private static StatsDClientManager statsDClientManager() throws Exception {
    final Class<?> statsdClientManagerClass =
        AGENT_CLASSLOADER.loadClass("datadog.communication.monitor.DDAgentStatsDClientManager");
    final Method statsDClientManagerMethod =
        statsdClientManagerClass.getMethod("statsDClientManager");
    return (StatsDClientManager) statsDClientManagerMethod.invoke(null);
  }

  private static void maybeStartAppSec(Class<?> scoClass, Object o) {
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
    final String arch = System.getProperty("os.arch");
    if (Platform.isWindows()) {
      // TODO: Windows bindings need to be built for x86
      return "amd64".equals(arch) || "x86_64".equals(arch);
    } else if (Platform.isMac()) {
      return "amd64".equals(arch) || "x86_64".equals(arch) || "aarch64".equals(arch);
    } else if (Platform.isLinux()) {
      return "amd64".equals(arch) || "x86_64".equals(arch) || "aarch64".equals(arch);
    }
    // Still return true in other if unexpected cases (e.g. SunOS), and we'll handle loading errors
    // during AppSec startup.
    return true;
  }

  private static void maybeStartIast(Class<?> scoClass, Object o) {
    if (iastEnabled || !iastFullyDisabled) {

      StaticEventLogger.begin("IAST");

      try {
        SubscriptionService ss = AgentTracer.get().getSubscriptionService(RequestContextSlot.IAST);
        startIast(ss, scoClass, o);
      } catch (Exception e) {
        log.error("Error starting IAST subsystem", e);
      }

      StaticEventLogger.end("IAST");
    }
  }

  private static void startIast(SubscriptionService ss, Class<?> scoClass, Object sco) {
    try {
      final Class<?> appSecSysClass = AGENT_CLASSLOADER.loadClass("com.datadog.iast.IastSystem");
      final Method iastInstallerMethod =
          appSecSysClass.getMethod("start", SubscriptionService.class);
      iastInstallerMethod.invoke(null, ss);
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

  private static void initializeCrashUploader() {
    if (Platform.isJ9()) {
      // TODO currently crash tracking is supported only for HotSpot based JVMs
      return;
    }
    try {
      Class<?> clz = AGENT_CLASSLOADER.loadClass("com.datadog.crashtracking.ScriptInitializer");
      clz.getMethod("initialize").invoke(null);
    } catch (Throwable t) {
      log.debug("Unable to initialize crash uploader", t);
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
      if (Config.get().isDatadogProfilerEnabled() && !Platform.isWindows()) {
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

  private static void startProfilingAgent(final boolean isStartingFirst, Instrumentation inst) {
    StaticEventLogger.begin("ProfilingAgent");

    if (isAwsLambdaRuntime()) {
      log.info("Profiling not supported in AWS Lambda runtimes");
      return;
    }

    final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(AGENT_CLASSLOADER);
      final Class<?> profilingAgentClass =
          AGENT_CLASSLOADER.loadClass("com.datadog.profiling.agent.ProfilingAgent");
      final Method profilingInstallerMethod =
          profilingAgentClass.getMethod(
              "run", Boolean.TYPE, ClassLoader.class, Instrumentation.class);
      profilingInstallerMethod.invoke(null, isStartingFirst, AGENT_CLASSLOADER, inst);
      /*
       * Install the tracer hooks only when not using 'early start'.
       * The 'early start' is happening so early that most of the infrastructure has not been set up yet.
       */
      if (!isStartingFirst) {
        log.debug("Scheduling scope event factory registration");
        WithGlobalTracer.registerOrExecute(
            new WithGlobalTracer.Callback() {
              @Override
              public void withTracer(TracerAPI tracer) {
                log.debug("Initializing profiler tracer integrations");
                tracer.getProfilingContext().onStart();
              }
            });
      }
    } catch (final Throwable ex) {
      log.error("Throwable thrown while starting profiling agent", ex);
    } finally {
      Thread.currentThread().setContextClassLoader(contextLoader);
    }

    StaticEventLogger.end("ProfilingAgent");
  }

  private static synchronized void startJfrMetrics() {
    if (!isJavaVersionAtLeast(17) || Platform.isJ9()) {
      log.debug("JFR metrics are only supported on Java 17+ and HotSpot JVMs");
      return;
    }
    final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(AGENT_CLASSLOADER);
      final Class<?> jfrMetricsClass =
          AGENT_CLASSLOADER.loadClass("datadog.trace.agent.jfrmetrics.JfrMetricsEmitter");
      final Method installerMethod = jfrMetricsClass.getMethod("run", StatsDClientManager.class);
      installerMethod.invoke(null, statsDClientManager());
    } catch (final Throwable ex) {
      log.error("Throwable thrown while starting JfrMetricsEmitter", ex);
    } finally {
      Thread.currentThread().setContextClassLoader(contextLoader);
    }
  }

  private static boolean isAwsLambdaRuntime() {
    String val = System.getenv("AWS_LAMBDA_FUNCTION_NAME");
    return val != null && !val.isEmpty();
  }

  private static ScopeListener createScopeListener(String className) throws Throwable {
    return (ScopeListener)
        AGENT_CLASSLOADER.loadClass(className).getDeclaredConstructor().newInstance();
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
      Thread.currentThread().setContextClassLoader(contextLoader);
    }
  }

  private static void maybeStartDebugger(Instrumentation inst, Class<?> scoClass, Object sco) {
    if (!debuggerEnabled) {
      return;
    }
    if (!remoteConfigEnabled) {
      log.warn("Cannot enable Dynamic Instrumentation because Remote Configuration is not enabled");
      return;
    }
    startDebuggerAgent(inst, scoClass, sco);
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
      Thread.currentThread().setContextClassLoader(contextLoader);
    }

    StaticEventLogger.end("Debugger");
  }

  private static void configureLogger() {
    setSystemPropertyDefault(SIMPLE_LOGGER_SHOW_DATE_TIME_PROPERTY, "true");
    setSystemPropertyDefault(
        SIMPLE_LOGGER_DATE_TIME_FORMAT_PROPERTY, SIMPLE_LOGGER_DATE_TIME_FORMAT_DEFAULT);

    if (isDebugMode()) {
      setSystemPropertyDefault(SIMPLE_LOGGER_DEFAULT_LOG_LEVEL_PROPERTY, "DEBUG");
    } else if (!isFeatureEnabled(AgentFeature.STARTUP_LOGS)) {
      setSystemPropertyDefault(SIMPLE_LOGGER_DEFAULT_LOG_LEVEL_PROPERTY, "WARN");
    }
  }

  private static void setSystemPropertyDefault(final String property, final String value) {
    if (System.getProperty(property) == null && ddGetEnv(property) == null) {
      System.setProperty(property, value);
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
    final String tracerDebugLevelProp = System.getProperty(tracerDebugLevelSysprop);

    if (tracerDebugLevelProp != null) {
      return Boolean.parseBoolean(tracerDebugLevelProp);
    }

    final String tracerDebugLevelEnv = ddGetEnv(tracerDebugLevelSysprop);

    if (tracerDebugLevelEnv != null) {
      return Boolean.parseBoolean(tracerDebugLevelEnv);
    }
    return false;
  }

  /** @return {@code true} if the agent feature is enabled */
  private static boolean isFeatureEnabled(AgentFeature feature) {
    // must be kept in sync with logic from Config!
    final String featureEnabledSysprop = feature.getSystemProp();
    String featureEnabled = System.getProperty(featureEnabledSysprop);
    if (featureEnabled == null) {
      featureEnabled = ddGetEnv(featureEnabledSysprop);
    }

    if (feature.isEnabledByDefault()) {
      // true unless it's explicitly set to "false"
      return !("false".equalsIgnoreCase(featureEnabled) || "0".equals(featureEnabled));
    } else {
      // false unless it's explicitly set to "true"
      return Boolean.parseBoolean(featureEnabled) || "1".equals(featureEnabled);
    }
  }

  /** @see datadog.trace.api.ProductActivation#fromString(String) */
  private static boolean isFullyDisabled(final AgentFeature feature) {
    // must be kept in sync with logic from Config!
    final String featureEnabledSysprop = feature.systemProp;
    String settingValue = getNullIfEmpty(System.getProperty(featureEnabledSysprop));
    if (settingValue == null) {
      settingValue = getNullIfEmpty(ddGetEnv(featureEnabledSysprop));
      settingValue = settingValue != null && settingValue.isEmpty() ? null : settingValue;
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

  /** @return configured JMX start delay in seconds */
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
    final String customLogManagerProp = System.getProperty(tracerCustomLogManSysprop);
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

    final String logManagerProp = System.getProperty("java.util.logging.manager");
    if (logManagerProp != null) {
      final boolean onSysClasspath =
          ClassLoader.getSystemResource(getResourceName(logManagerProp)) != null;
      log.debug("Prop - logging.manager: {}", logManagerProp);
      log.debug("logging.manager on system classpath: {}", onSysClasspath);
      // Some applications set java.util.logging.manager but never actually initialize the logger.
      // Check to see if the configured manager is on the system classpath.
      // If so, it should be safe to initialize jmxfetch which will setup the log manager.
      return !onSysClasspath;
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
    final String customJMXBuilderProp = System.getProperty(tracerCustomJMXBuilderSysprop);
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

    final String jmxBuilderProp = System.getProperty("javax.management.builder.initial");
    if (jmxBuilderProp != null) {
      final boolean onSysClasspath =
          ClassLoader.getSystemResource(getResourceName(jmxBuilderProp)) != null;
      log.debug("Prop - javax.management.builder.initial: {}", jmxBuilderProp);
      log.debug("javax.management.builder.initial on system classpath: {}", onSysClasspath);
      // Some applications set javax.management.builder.initial but never actually initialize JMX.
      // Check to see if the configured JMX builder is on the system classpath.
      // If so, it should be safe to initialize jmxfetch which will setup JMX.
      return !onSysClasspath;
    }

    return false;
  }

  /** Looks for the "dd." system property first then the "DD_" environment variable equivalent. */
  private static String ddGetProperty(final String sysProp) {
    String value = System.getProperty(sysProp);
    if (null == value) {
      value = ddGetEnv(sysProp);
    }
    return value;
  }

  /** Looks for the "DD_" environment variable equivalent of the given "dd." system property. */
  private static String ddGetEnv(final String sysProp) {
    return System.getenv(toEnvVar(sysProp));
  }

  private static boolean okHttpMayIndirectlyLoadJUL() {
    if ("IBM Corporation".equals(getRuntimeVendor())) {
      return true; // IBM JDKs ship with 'IBMSASL' which will load JUL when OkHttp accesses TLS
    }
    if (isJavaVersionAtLeast(9)) {
      return false; // JDKs since 9 have reworked JFR to use a different logging facility, not JUL
    }
    return isJFRSupported(); // assume OkHttp will indirectly load JUL via its JFR events
  }

  private static boolean isJFRSupported() {
    // FIXME: this is quite a hack because there maybe jfr classes on classpath somehow that have
    // nothing to do with JDK - but this should be safe because only thing this does is to delay
    // tracer install
    return BootstrapProxy.INSTANCE.getResource("jdk/jfr/Recording.class") != null;
  }
}
