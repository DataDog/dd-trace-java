package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.REMOTE_CONFIG;
import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import com.datadog.debugger.codeorigin.DefaultCodeOriginRecorder;
import com.datadog.debugger.exception.DefaultExceptionDebugger;
import com.datadog.debugger.exception.ExceptionProbeManager;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.sink.SnapshotSink;
import com.datadog.debugger.sink.SymbolSink;
import com.datadog.debugger.symbol.SymDBEnablement;
import com.datadog.debugger.symbol.SymbolAggregator;
import com.datadog.debugger.uploader.BatchUploader;
import com.datadog.debugger.util.ClassNameFiltering;
import com.datadog.debugger.util.DebuggerMetrics;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.trace.api.Config;
import datadog.trace.api.flare.TracerFlare;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DebuggerContext.ClassNameFilter;
import datadog.trace.bootstrap.debugger.util.Redaction;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDTraceCoreInfo;
import datadog.trace.util.SizeCheckedInputStream;
import datadog.trace.util.TagsHelper;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Debugger agent implementation */
public class DebuggerAgent {
  private static final Logger LOGGER = LoggerFactory.getLogger(DebuggerAgent.class);
  private static ConfigurationPoller configurationPoller;
  private static DebuggerSink sink;
  private static String agentVersion;
  private static JsonSnapshotSerializer snapshotSerializer;
  private static SymDBEnablement symDBEnablement;

  public static synchronized void run(
      Instrumentation instrumentation, SharedCommunicationObjects sco) {
    Config config = Config.get();
    ClassesToRetransformFinder classesToRetransformFinder = new ClassesToRetransformFinder();
    setupSourceFileTracking(instrumentation, classesToRetransformFinder);
    Redaction.addUserDefinedKeywords(config);
    Redaction.addUserDefinedTypes(config);
    DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery = sco.featuresDiscovery(config);
    ddAgentFeaturesDiscovery.discoverIfOutdated();
    agentVersion = ddAgentFeaturesDiscovery.getVersion();
    String diagnosticEndpoint = getDiagnosticEndpoint(config, ddAgentFeaturesDiscovery);
    ProbeStatusSink probeStatusSink =
        new ProbeStatusSink(
            config, diagnosticEndpoint, ddAgentFeaturesDiscovery.supportsDebuggerDiagnostics());
    DebuggerSink debuggerSink = createDebuggerSink(config, probeStatusSink);
    debuggerSink.start();
    ClassNameFilter classNameFilter = new ClassNameFiltering(config);
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            instrumentation,
            DebuggerAgent::createTransformer,
            config,
            debuggerSink,
            classesToRetransformFinder);
    sink = debuggerSink;
    StatsdMetricForwarder statsdMetricForwarder =
        new StatsdMetricForwarder(config, probeStatusSink);
    DebuggerContext.initProbeResolver(configurationUpdater);
    DebuggerContext.initMetricForwarder(statsdMetricForwarder);
    DebuggerContext.initClassFilter(new DenyListHelper(null)); // default hard coded deny list
    snapshotSerializer = new JsonSnapshotSerializer();
    DebuggerContext.initValueSerializer(snapshotSerializer);
    DebuggerContext.initTracer(new DebuggerTracer(debuggerSink.getProbeStatusSink()));
    DebuggerContext.initClassNameFilter(classNameFilter);
    DefaultExceptionDebugger defaultExceptionDebugger = null;
    if (config.isDebuggerExceptionEnabled()) {
      LOGGER.info("Starting Exception Replay");
      defaultExceptionDebugger =
          new DefaultExceptionDebugger(
              configurationUpdater,
              classNameFilter,
              Duration.ofSeconds(config.getDebuggerExceptionCaptureInterval()),
              config.getDebuggerMaxExceptionPerSecond());
      DebuggerContext.initExceptionDebugger(defaultExceptionDebugger);
    }
    if (config.isDebuggerCodeOriginEnabled()) {
      LOGGER.info("Starting Code Origin for spans");
      DebuggerContext.initCodeOrigin(new DefaultCodeOriginRecorder(config, configurationUpdater));
    }
    if (config.isDebuggerInstrumentTheWorld()) {
      setupInstrumentTheWorldTransformer(
          config, instrumentation, debuggerSink, statsdMetricForwarder);
    }
    // Dynamic Instrumentation
    if (config.isDebuggerEnabled()) {
      startDynamicInstrumentation(
          instrumentation, sco, config, configurationUpdater, debuggerSink, classNameFilter);
    }
    try {
      /*
      Note: shutdown hooks are tricky because JVM holds reference for them forever preventing
      GC for anything that is reachable from it.
       */
      Runtime.getRuntime().addShutdownHook(new ShutdownHook(configurationPoller, debuggerSink));
    } catch (final IllegalStateException ex) {
      // The JVM is already shutting down.
    }
    ExceptionProbeManager exceptionProbeManager =
        defaultExceptionDebugger != null
            ? defaultExceptionDebugger.getExceptionProbeManager()
            : null;
    TracerFlare.addReporter(
        new DebuggerReporter(configurationUpdater, sink, exceptionProbeManager));
  }

  private static void startDynamicInstrumentation(
      Instrumentation instrumentation,
      SharedCommunicationObjects sco,
      Config config,
      ConfigurationUpdater configurationUpdater,
      DebuggerSink debuggerSink,
      ClassNameFilter classNameFilter) {
    LOGGER.info("Starting Dynamic Instrumentation");
    String probeFileLocation = config.getDebuggerProbeFileLocation();
    if (probeFileLocation != null) {
      Path probeFilePath = Paths.get(probeFileLocation);
      loadFromFile(probeFilePath, configurationUpdater, config.getDebuggerMaxPayloadSize());
      return;
    }
    configurationPoller = sco.configurationPoller(config);
    if (configurationPoller != null) {
      if (config.isDebuggerSymbolEnabled()) {
        symDBEnablement =
            new SymDBEnablement(
                instrumentation,
                config,
                new SymbolAggregator(
                    debuggerSink.getSymbolSink(), config.getDebuggerSymbolFlushThreshold()),
                classNameFilter);
        if (config.isDebuggerSymbolForceUpload()) {
          symDBEnablement.startSymbolExtraction();
        }
      }
      subscribeConfigurationPoller(config, configurationUpdater, symDBEnablement);
    } else {
      LOGGER.debug("No configuration poller available from SharedCommunicationObjects");
    }
  }

  private static DebuggerSink createDebuggerSink(Config config, ProbeStatusSink probeStatusSink) {
    String tags = getDefaultTagsMergedWithGlobalTags(config);
    SnapshotSink snapshotSink =
        new SnapshotSink(
            config,
            tags,
            new BatchUploader(
                config, config.getFinalDebuggerSnapshotUrl(), SnapshotSink.RETRY_POLICY));
    SymbolSink symbolSink = new SymbolSink(config);
    return new DebuggerSink(
        config,
        tags,
        DebuggerMetrics.getInstance(config),
        probeStatusSink,
        snapshotSink,
        symbolSink);
  }

  public static String getDefaultTagsMergedWithGlobalTags(Config config) {
    GitInfo gitInfo = GitInfoProvider.INSTANCE.getGitInfo();
    String gitSha = gitInfo.getCommit().getSha();
    String gitUrl = gitInfo.getRepositoryURL();
    String debuggerTags =
        TagsHelper.concatTags(
            "env:" + config.getEnv(),
            "version:" + config.getVersion(),
            "debugger_version:" + DDTraceCoreInfo.VERSION,
            "agent_version:" + DebuggerAgent.getAgentVersion(),
            "host_name:" + config.getHostName(),
            gitSha != null ? Tags.GIT_COMMIT_SHA + ":" + gitSha : null,
            gitUrl != null ? Tags.GIT_REPOSITORY_URL + ":" + gitUrl : null);
    if (config.getGlobalTags().isEmpty()) {
      return debuggerTags;
    }
    String globalTags =
        config.getGlobalTags().entrySet().stream()
            .map(e -> e.getKey() + ":" + e.getValue())
            .collect(Collectors.joining(","));
    return debuggerTags + "," + globalTags;
  }

  private static String getDiagnosticEndpoint(
      Config config, DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery) {
    if (ddAgentFeaturesDiscovery.supportsDebuggerDiagnostics()) {
      return ddAgentFeaturesDiscovery
          .buildUrl(DDAgentFeaturesDiscovery.DEBUGGER_DIAGNOSTICS_ENDPOINT)
          .toString();
    }
    return config.getFinalDebuggerSnapshotUrl();
  }

  private static void setupSourceFileTracking(
      Instrumentation instrumentation, ClassesToRetransformFinder finder) {
    instrumentation.addTransformer(new SourceFileTrackingTransformer(finder));
  }

  private static void loadFromFile(
      Path probeFilePath, ConfigurationUpdater configurationUpdater, long maxPayloadSize) {
    LOGGER.debug("try to load from file...");
    try (InputStream inputStream =
        new SizeCheckedInputStream(new FileInputStream(probeFilePath.toFile()), maxPayloadSize)) {
      byte[] buffer = new byte[4096];
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream(4096);
      int bytesRead;
      do {
        bytesRead = inputStream.read(buffer);
        if (bytesRead > -1) {
          outputStream.write(buffer, 0, bytesRead);
        }
      } while (bytesRead > -1);
      Configuration configuration =
          DebuggerProductChangesListener.Adapter.deserializeConfiguration(
              outputStream.toByteArray());
      LOGGER.debug("Probe definitions loaded from file {}", probeFilePath);
      configurationUpdater.accept(REMOTE_CONFIG, configuration.getDefinitions());
    } catch (IOException ex) {
      LOGGER.error("Unable to load config file {}: {}", probeFilePath, ex);
    }
  }

  private static void subscribeConfigurationPoller(
      Config config, ConfigurationUpdater configurationUpdater, SymDBEnablement symDBEnablement) {
    LOGGER.debug("Subscribing to Live Debugging...");
    configurationPoller.addListener(
        Product.LIVE_DEBUGGING, new DebuggerProductChangesListener(config, configurationUpdater));
    if (symDBEnablement != null && !config.isDebuggerSymbolForceUpload()) {
      LOGGER.debug("Subscribing to Symbol DB...");
      configurationPoller.addListener(Product.LIVE_DEBUGGING_SYMBOL_DB, symDBEnablement);
    }
  }

  static ClassFileTransformer setupInstrumentTheWorldTransformer(
      Config config,
      Instrumentation instrumentation,
      DebuggerSink debuggerSink,
      StatsdMetricForwarder statsdMetricForwarder) {
    LOGGER.info("install Instrument-The-World transformer");
    DebuggerTransformer transformer =
        createTransformer(config, Configuration.builder().build(), null, debuggerSink);
    DebuggerContext.initProbeResolver(transformer::instrumentTheWorldResolver);
    DebuggerContext.initMetricForwarder(statsdMetricForwarder);
    instrumentation.addTransformer(transformer);
    return transformer;
  }

  public static String getAgentVersion() {
    return agentVersion;
  }

  public static DebuggerSink getSink() {
    return sink;
  }

  private static DebuggerTransformer createTransformer(
      Config config,
      Configuration configuration,
      DebuggerTransformer.InstrumentationListener listener,
      DebuggerSink debuggerSink) {
    return new DebuggerTransformer(config, configuration, listener, debuggerSink);
  }

  static void stop() {
    if (configurationPoller != null) {
      configurationPoller.stop();
    }
    if (sink != null) {
      sink.stop();
    }
  }

  // Used only for tests
  static void initSink(DebuggerSink sink) {
    DebuggerAgent.sink = sink;
  }

  // Used only for tests
  static void initSnapshotSerializer(JsonSnapshotSerializer snapshotSerializer) {
    DebuggerAgent.snapshotSerializer = snapshotSerializer;
  }

  public static JsonSnapshotSerializer getSnapshotSerializer() {
    return DebuggerAgent.snapshotSerializer;
  }

  private static class ShutdownHook extends Thread {

    private final WeakReference<ConfigurationPoller> pollerRef;
    private final WeakReference<DebuggerSink> sinkRef;

    private ShutdownHook(ConfigurationPoller poller, DebuggerSink debuggerSink) {
      super(AGENT_THREAD_GROUP, "dd-debugger-shutdown-hook");
      pollerRef = new WeakReference<>(poller);
      sinkRef = new WeakReference<>(debuggerSink);
    }

    @Override
    public void run() {
      final ConfigurationPoller poller = pollerRef.get();
      if (poller != null) {
        try {
          poller.stop();
        } catch (Exception ex) {
          LOGGER.warn("failed to shutdown ProbesPoller: ", ex);
        }
      }

      final DebuggerSink sink = sinkRef.get();
      if (sink != null) {
        try {
          sink.stop();
        } catch (Exception ex) {
          LOGGER.warn("Failed to shutdown SnapshotUploader", ex);
        }
      }
    }
  }

  static class DebuggerReporter implements TracerFlare.Reporter {

    private final ConfigurationUpdater configurationUpdater;
    private final DebuggerSink sink;
    private final ExceptionProbeManager exceptionProbeManager;

    public DebuggerReporter(
        ConfigurationUpdater configurationUpdater,
        DebuggerSink sink,
        ExceptionProbeManager exceptionProbeManager) {
      this.configurationUpdater = configurationUpdater;
      this.sink = sink;
      this.exceptionProbeManager = exceptionProbeManager;
    }

    @Override
    public void addReportToFlare(ZipOutputStream zip) throws IOException {
      String content =
          String.join(
              System.lineSeparator(),
              "Snapshot url: ",
              sink.getSnapshotSink().getUrl().toString(),
              "Diagnostic url: ",
              sink.getProbeStatusSink().getUrl().toString(),
              "SymbolDB url: ",
              sink.getSymbolSink().getUrl().toString(),
              "Probe definitions:",
              configurationUpdater.getAppliedDefinitions().toString(),
              "Instrumented probes:",
              configurationUpdater.getInstrumentationResults().toString(),
              "Probe statuses:",
              sink.getProbeStatusSink().getProbeStatuses().toString(),
              "SymbolDB stats:",
              sink.getSymbolSink().getStats().toString(),
              "Exception Fingerprints:",
              exceptionProbeManager != null
                  ? exceptionProbeManager.getFingerprints().toString()
                  : "N/A");
      TracerFlare.addText(zip, "dynamic_instrumentation.txt", content);
    }
  }
}
