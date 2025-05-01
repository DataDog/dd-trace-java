package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.CODE_ORIGIN;
import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.EXCEPTION;
import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.REMOTE_CONFIG;
import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import com.datadog.debugger.codeorigin.DefaultCodeOriginRecorder;
import com.datadog.debugger.exception.DefaultExceptionDebugger;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.sink.SnapshotSink;
import com.datadog.debugger.sink.SymbolSink;
import com.datadog.debugger.symbol.AvroFilter;
import com.datadog.debugger.symbol.ProtoFilter;
import com.datadog.debugger.symbol.ScopeFilter;
import com.datadog.debugger.symbol.SymDBEnablement;
import com.datadog.debugger.symbol.SymbolAggregator;
import com.datadog.debugger.symbol.WireFilter;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Debugger agent implementation */
public class DebuggerAgent {
  private static final Logger LOGGER = LoggerFactory.getLogger(DebuggerAgent.class);
  private static Instrumentation instrumentation;
  private static SharedCommunicationObjects sharedCommunicationObjects;
  private static ConfigurationPoller configurationPoller;
  private static DebuggerSink sink;
  private static String agentVersion;
  private static JsonSnapshotSerializer snapshotSerializer;
  private static volatile ClassNameFilter classNameFilter;
  private static volatile SymDBEnablement symDBEnablement;
  private static volatile ConfigurationUpdater configurationUpdater;
  private static volatile DefaultExceptionDebugger exceptionDebugger;
  private static final AtomicBoolean commonInitDone = new AtomicBoolean();
  static final AtomicBoolean dynamicInstrumentationEnabled = new AtomicBoolean();
  static final AtomicBoolean exceptionReplayEnabled = new AtomicBoolean();
  static final AtomicBoolean codeOriginEnabled = new AtomicBoolean();
  static final AtomicBoolean distributedDebuggerEnabled = new AtomicBoolean();
  private static ClassesToRetransformFinder classesToRetransformFinder;

  public static synchronized void run(Instrumentation inst, SharedCommunicationObjects sco) {
    instrumentation = inst;
    sharedCommunicationObjects = sco;
    Config config = Config.get();
    DebuggerContext.initProductConfigUpdater(new DefaultProductConfigUpdater());
    classesToRetransformFinder = new ClassesToRetransformFinder();
    setupSourceFileTracking(instrumentation, classesToRetransformFinder);
    if (config.isDebuggerCodeOriginEnabled()) {
      startCodeOriginForSpans();
    }
    if (config.isDebuggerExceptionEnabled()) {
      startExceptionReplay();
    }
    if (config.isDynamicInstrumentationEnabled()) {
      startDynamicInstrumentation();
      if (config.isDynamicInstrumentationInstrumentTheWorld()) {
        setupInstrumentTheWorldTransformer(config, instrumentation, sink);
      }
    }
    try {
      /*
      Note: shutdown hooks are tricky because JVM holds reference for them forever preventing
      GC for anything that is reachable from it.
       */
      Runtime.getRuntime().addShutdownHook(new ShutdownHook(configurationPoller, sink));
    } catch (final IllegalStateException ex) {
      // The JVM is already shutting down.
    }
    TracerFlare.addReporter(DebuggerAgent::addReportToFlare);
  }

  private static void commonInit(Config config) {
    if (!commonInitDone.compareAndSet(false, true)) {
      return;
    }
    configurationPoller = sharedCommunicationObjects.configurationPoller(config);
    Redaction.addUserDefinedKeywords(config);
    Redaction.addUserDefinedTypes(config);
    DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery =
        sharedCommunicationObjects.featuresDiscovery(config);
    ddAgentFeaturesDiscovery.discoverIfOutdated();
    agentVersion = ddAgentFeaturesDiscovery.getVersion();
    String diagnosticEndpoint = getDiagnosticEndpoint(config, ddAgentFeaturesDiscovery);
    ProbeStatusSink probeStatusSink =
        new ProbeStatusSink(
            config, diagnosticEndpoint, ddAgentFeaturesDiscovery.supportsDebuggerDiagnostics());
    DebuggerSink debuggerSink = createDebuggerSink(config, probeStatusSink);
    debuggerSink.start();
    configurationUpdater =
        new ConfigurationUpdater(
            instrumentation,
            DebuggerAgent::createTransformer,
            config,
            debuggerSink,
            classesToRetransformFinder);
    sink = debuggerSink;
    DebuggerContext.initProbeResolver(configurationUpdater);
    DebuggerContext.initMetricForwarder(new StatsdMetricForwarder(config, probeStatusSink));
    DebuggerContext.initClassFilter(new DenyListHelper(null)); // default hard coded deny list
    snapshotSerializer = new JsonSnapshotSerializer();
    DebuggerContext.initValueSerializer(snapshotSerializer);
    DebuggerContext.initTracer(new DebuggerTracer(debuggerSink.getProbeStatusSink()));
  }

  private static void initClassNameFilter() {
    if (classNameFilter == null) {
      classNameFilter = new ClassNameFiltering(Config.get());
    }
  }

  public static void startDynamicInstrumentation() {
    if (!dynamicInstrumentationEnabled.compareAndSet(false, true)) {
      return;
    }
    LOGGER.info("Starting Dynamic Instrumentation");
    Config config = Config.get();
    commonInit(config);
    String probeFileLocation = config.getDynamicInstrumentationProbeFile();
    if (probeFileLocation != null) {
      Path probeFilePath = Paths.get(probeFileLocation);
      loadFromFile(
          probeFilePath, configurationUpdater, config.getDynamicInstrumentationMaxPayloadSize());
      return;
    }
    if (configurationPoller != null) {
      if (config.isSymbolDatabaseEnabled()) {
        initClassNameFilter();
        List<ScopeFilter> scopeFilters =
            Arrays.asList(new AvroFilter(), new ProtoFilter(), new WireFilter());
        SymbolAggregator symbolAggregator =
            new SymbolAggregator(
                classNameFilter,
                scopeFilters,
                sink.getSymbolSink(),
                config.getSymbolDatabaseFlushThreshold());
        symbolAggregator.start();
        symDBEnablement =
            new SymDBEnablement(instrumentation, config, symbolAggregator, classNameFilter);
        if (config.isSymbolDatabaseForceUpload()) {
          symDBEnablement.startSymbolExtraction();
        }
      }
      subscribeConfigurationPoller(config, configurationUpdater, symDBEnablement);
    } else {
      LOGGER.debug("No configuration poller available from SharedCommunicationObjects");
    }
    LOGGER.info("Started Dynamic Instrumentation");
  }

  public static void stopDynamicInstrumentation() {
    if (!dynamicInstrumentationEnabled.compareAndSet(true, false)) {
      return;
    }
    LOGGER.info("Stopping Dynamic Instrumentation");
    unsubscribeConfigurationPoller();
    if (configurationUpdater != null) {
      // uninstall all probes by providing empty configuration
      configurationUpdater.accept(REMOTE_CONFIG, Collections.emptyList());
    }
    if (symDBEnablement != null) {
      symDBEnablement.stopSymbolExtraction();
      symDBEnablement = null;
    }
  }

  public static void startExceptionReplay() {
    if (!exceptionReplayEnabled.compareAndSet(false, true)) {
      return;
    }
    LOGGER.info("Starting Exception Replay");
    Config config = Config.get();
    commonInit(config);
    initClassNameFilter();
    exceptionDebugger =
        new DefaultExceptionDebugger(
            configurationUpdater,
            classNameFilter,
            Duration.ofSeconds(config.getDebuggerExceptionCaptureInterval()),
            config.getDebuggerMaxExceptionPerSecond());
    DebuggerContext.initExceptionDebugger(exceptionDebugger);
    LOGGER.info("Started Exception Replay");
  }

  public static void stopExceptionReplay() {
    if (!exceptionReplayEnabled.compareAndSet(true, false)) {
      return;
    }
    LOGGER.info("Stopping Exception Replay");
    if (configurationUpdater != null) {
      // uninstall all exception probes by providing empty configuration
      configurationUpdater.accept(EXCEPTION, Collections.emptyList());
    }
    exceptionDebugger = null;
    DebuggerContext.initExceptionDebugger(null);
  }

  public static void startCodeOriginForSpans() {
    if (!codeOriginEnabled.compareAndSet(false, true)) {
      return;
    }
    LOGGER.info("Starting Code Origin for spans");
    Config config = Config.get();
    commonInit(config);
    initClassNameFilter();
    DebuggerContext.initClassNameFilter(classNameFilter);
    DebuggerContext.initCodeOrigin(new DefaultCodeOriginRecorder(config, configurationUpdater));
    LOGGER.info("Started Code Origin for spans");
  }

  public static void stopCodeOriginForSpans() {
    if (!codeOriginEnabled.compareAndSet(true, false)) {
      return;
    }
    LOGGER.info("Stopping Code Origin for spans");
    if (configurationUpdater != null) {
      // uninstall all code origin probes by providing empty configuration
      configurationUpdater.accept(CODE_ORIGIN, Collections.emptyList());
    }
    DebuggerContext.initCodeOrigin(null);
  }

  public static void startDistributedDebugger() {
    if (!distributedDebuggerEnabled.compareAndSet(false, true)) {
      return;
    }
    LOGGER.info("Starting Distributed Debugger");
  }

  public static void stopDistributedDebugger() {
    if (!distributedDebuggerEnabled.compareAndSet(true, false)) {
      return;
    }
    LOGGER.info("Sopping Distributed Debugger");
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
    SourceFileTrackingTransformer sourceFileTrackingTransformer =
        new SourceFileTrackingTransformer(finder);
    sourceFileTrackingTransformer.start();
    instrumentation.addTransformer(sourceFileTrackingTransformer);
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
    if (symDBEnablement != null && !config.isSymbolDatabaseForceUpload()) {
      LOGGER.debug("Subscribing to Symbol DB...");
      configurationPoller.addListener(Product.LIVE_DEBUGGING_SYMBOL_DB, symDBEnablement);
    }
  }

  private static void unsubscribeConfigurationPoller() {
    if (configurationPoller != null) {
      configurationPoller.removeListeners(Product.LIVE_DEBUGGING);
      configurationPoller.removeListeners(Product.LIVE_DEBUGGING_SYMBOL_DB);
    }
  }

  static ClassFileTransformer setupInstrumentTheWorldTransformer(
      Config config, Instrumentation instrumentation, DebuggerSink debuggerSink) {
    LOGGER.info("install Instrument-The-World transformer");
    DebuggerTransformer transformer =
        createTransformer(config, Configuration.builder().build(), null, debuggerSink);
    DebuggerContext.initProbeResolver(transformer::instrumentTheWorldResolver);
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

  private static void addReportToFlare(ZipOutputStream zip) throws IOException {
    String snapshotUrl = null;
    String diagnosticUrl = null;
    String symbolDbUrl = null;
    String probeStatuses = null;
    String symbolDBStats = null;
    if (sink != null) {
      snapshotUrl = sink.getSnapshotSink().getUrl().toString();
      diagnosticUrl = sink.getProbeStatusSink().getUrl().toString();
      symbolDbUrl = sink.getSymbolSink().getUrl().toString();
      probeStatuses = sink.getProbeStatusSink().getProbeStatuses().toString();
      symbolDBStats = sink.getSymbolSink().getStats().toString();
    }
    String probeDefinitions = null;
    String instrumentedProbes = null;
    if (configurationUpdater != null) {
      probeDefinitions = configurationUpdater.getAppliedDefinitions().toString();
      instrumentedProbes = configurationUpdater.getInstrumentationResults().toString();
    }
    String exceptionFingerprints = null;
    if (exceptionDebugger != null) {
      exceptionFingerprints =
          exceptionDebugger.getExceptionProbeManager().getFingerprints().toString();
    }
    String content =
        String.join(
            System.lineSeparator(),
            "Snapshot url: ",
            snapshotUrl,
            "Diagnostic url: ",
            diagnosticUrl,
            "SymbolDB url: ",
            symbolDbUrl,
            "Probe definitions:",
            probeDefinitions,
            "Instrumented probes:",
            instrumentedProbes,
            "Probe statuses:",
            probeStatuses,
            "SymbolDB stats:",
            symbolDBStats,
            "Exception Fingerprints:",
            exceptionFingerprints);
    TracerFlare.addText(zip, "dynamic_instrumentation.txt", content);
  }
}
