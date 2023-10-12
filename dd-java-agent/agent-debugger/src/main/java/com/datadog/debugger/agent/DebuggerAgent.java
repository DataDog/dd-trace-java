package com.datadog.debugger.agent;

import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.Sink;
import com.datadog.debugger.uploader.BatchUploader;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.SizeCheckedInputStream;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Debugger agent implementation */
public class DebuggerAgent {
  private static final Logger log = LoggerFactory.getLogger(DebuggerAgent.class);
  private static ConfigurationPoller configurationPoller;
  private static Sink sink;
  private static String agentVersion;
  private static JsonSnapshotSerializer snapshotSerializer;

  public static synchronized void run(
      Instrumentation instrumentation, SharedCommunicationObjects sco) {
    Config config = Config.get();
    if (!config.isDebuggerEnabled()) {
      log.info("Debugger agent disabled");
      return;
    }
    log.info("Starting Dynamic Instrumentation");
    ClassesToRetransformFinder classesToRetransformFinder = new ClassesToRetransformFinder();
    setupSourceFileTracking(instrumentation, classesToRetransformFinder);
    String finalDebuggerSnapshotUrl = config.getFinalDebuggerSnapshotUrl();
    String agentUrl = config.getAgentUrl();
    boolean isSnapshotUploadThroughAgent = Objects.equals(finalDebuggerSnapshotUrl, agentUrl);

    DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery = sco.featuresDiscovery(config);
    ddAgentFeaturesDiscovery.discoverIfOutdated();
    agentVersion = ddAgentFeaturesDiscovery.getVersion();

    DebuggerSink debuggerSink = new DebuggerSink(config);
    debuggerSink.start();
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            instrumentation,
            DebuggerAgent::createTransformer,
            config,
            debuggerSink,
            classesToRetransformFinder);
    sink = debuggerSink;
    StatsdMetricForwarder statsdMetricForwarder = new StatsdMetricForwarder(config);
    DebuggerContext.init(configurationUpdater, statsdMetricForwarder);
    DebuggerContext.initClassFilter(new DenyListHelper(null)); // default hard coded deny list
    snapshotSerializer = new JsonSnapshotSerializer();
    DebuggerContext.initValueSerializer(snapshotSerializer);
    DebuggerContext.initTracer(new DebuggerTracer());
    if (config.isDebuggerInstrumentTheWorld()) {
      setupInstrumentTheWorldTransformer(
          config, instrumentation, debuggerSink, statsdMetricForwarder);
    }

    String probeFileLocation = config.getDebuggerProbeFileLocation();

    if (probeFileLocation != null) {
      Path probeFilePath = Paths.get(probeFileLocation);
      loadFromFile(probeFilePath, configurationUpdater, config.getDebuggerMaxPayloadSize());
      return;
    }

    configurationPoller = sco.configurationPoller(config);
    if (configurationPoller != null) {
      subscribeConfigurationPoller(config, configurationUpdater);

      try {
        /*
        Note: shutdown hooks are tricky because JVM holds reference for them forever preventing
        GC for anything that is reachable from it.
         */
        Runtime.getRuntime()
            .addShutdownHook(
                new ShutdownHook(configurationPoller, debuggerSink.getSnapshotUploader()));
      } catch (final IllegalStateException ex) {
        // The JVM is already shutting down.
      }
    } else {
      log.debug("No configuration poller available from SharedCommunicationObjects");
    }
  }

  private static void setupSourceFileTracking(
      Instrumentation instrumentation, ClassesToRetransformFinder finder) {
    instrumentation.addTransformer(new SourceFileTrackingTransformer(finder));
  }

  private static void loadFromFile(
      Path probeFilePath, ConfigurationUpdater configurationUpdater, long maxPayloadSize) {
    log.debug("try to load from file...");
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
      log.debug("Probe definitions loaded from file {}", probeFilePath);
      configurationUpdater.accept(configuration);
    } catch (IOException ex) {
      log.error("Unable to load config file {}: {}", probeFilePath, ex);
    }
  }

  private static void subscribeConfigurationPoller(
      Config config, ConfigurationUpdater configurationUpdater) {
    configurationPoller.addListener(
        Product.LIVE_DEBUGGING, new DebuggerProductChangesListener(config, configurationUpdater));
  }

  static ClassFileTransformer setupInstrumentTheWorldTransformer(
      Config config,
      Instrumentation instrumentation,
      DebuggerSink debuggerSink,
      StatsdMetricForwarder statsdMetricForwarder) {
    log.info("install Instrument-The-World transformer");
    DebuggerTransformer transformer =
        createTransformer(config, Configuration.builder().build(), null, debuggerSink);
    DebuggerContext.init(transformer::instrumentTheWorldResolver, statsdMetricForwarder);
    instrumentation.addTransformer(transformer);
    return transformer;
  }

  public static String getAgentVersion() {
    return agentVersion;
  }

  public static Sink getSink() {
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
    if (sink != null && sink instanceof DebuggerSink) {
      ((DebuggerSink) sink).stop();
    }
  }

  // Used only for tests
  static void initSink(Sink sink) {
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
    private final WeakReference<BatchUploader> uploaderRef;

    private ShutdownHook(ConfigurationPoller poller, BatchUploader uploader) {
      super(AGENT_THREAD_GROUP, "dd-debugger-shutdown-hook");
      pollerRef = new WeakReference<>(poller);
      uploaderRef = new WeakReference<>(uploader);
    }

    @Override
    public void run() {
      final ConfigurationPoller poller = pollerRef.get();
      if (poller != null) {
        try {
          poller.stop();
        } catch (Exception ex) {
          log.warn("failed to shutdown ProbesPoller: ", ex);
        }
      }

      final BatchUploader uploader = uploaderRef.get();
      if (uploader != null) {
        try {
          uploader.shutdown();
        } catch (Exception ex) {
          log.warn("Failed to shutdown SnapshotUploader", ex);
        }
      }
    }
  }
}
