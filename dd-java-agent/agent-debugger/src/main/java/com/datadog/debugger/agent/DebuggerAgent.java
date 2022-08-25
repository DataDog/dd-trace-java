package com.datadog.debugger.agent;

import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.uploader.BatchUploader;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.SizeCheckedInputStream;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Debugger agent implementation */
public class DebuggerAgent {
  private static final Logger log = LoggerFactory.getLogger(DebuggerAgent.class);
  private static ConfigurationPoller configurationPoller;
  private static DebuggerSink sink;
  private static String agentVersion;

  public static synchronized void run(
      Instrumentation instrumentation, SharedCommunicationObjects sco) {

    Config config = Config.get();

    if (!config.isDebuggerEnabled()) {
      log.info("Debugger agent disabled");
      return;
    }

    String finalDebuggerSnapshotUrl = config.getFinalDebuggerSnapshotUrl();
    String agentUrl = config.getAgentUrl();
    boolean isSnapshotUploadThroughAgent = Objects.equals(finalDebuggerSnapshotUrl, agentUrl);

    DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery = sco.featuresDiscovery(config);
    agentVersion = ddAgentFeaturesDiscovery.getVersion();

    if (isSnapshotUploadThroughAgent && !ddAgentFeaturesDiscovery.supportsDebugger()) {
      log.error(
          "No endpoint detected to upload snapshots to from datadog agent at "
              + agentUrl
              + ". Consider upgrading the datadog agent.");
      return;
    }
    if (ddAgentFeaturesDiscovery.getConfigEndpoint() == null) {
      log.error(
          "No endpoint detected to read probe config from datadog agent at "
              + agentUrl
              + ". Consider upgrading the datadog agent.");
      return;
    }

    sink = new DebuggerSink(config);
    sink.start();
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(instrumentation, DebuggerAgent::createTransformer, config, sink);
    StatsdMetricForwarder statsdMetricForwarder = new StatsdMetricForwarder(config);
    DebuggerContext.init(sink, configurationUpdater, statsdMetricForwarder);
    DebuggerContext.initClassFilter(new DenyListHelper(null)); // default hard coded deny list
    if (config.isDebuggerInstrumentTheWorld()) {
      setupInstrumentTheWorldTransformer(config, instrumentation, sink, statsdMetricForwarder);
    }

    String probeFileLocation = config.getDebuggerProbeFileLocation();

    if (probeFileLocation != null) {
      Path probeFilePath = Paths.get(probeFileLocation);
      loadFromFile(probeFilePath, configurationUpdater, config.getDebuggerMaxPayloadSize());
      return;
    }

    configurationPoller = (ConfigurationPoller) sco.configurationPoller(config);
    if (configurationPoller != null) {
      subscribeConfigurationPoller(configurationUpdater);
      configurationPoller.start();

      try {
        /*
        Note: shutdown hooks are tricky because JVM holds reference for them forever preventing
        GC for anything that is reachable from it.
         */
        Runtime.getRuntime()
            .addShutdownHook(new ShutdownHook(configurationPoller, sink.getSnapshotUploader()));
      } catch (final IllegalStateException ex) {
        // The JVM is already shutting down.
      }
    }
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
          ConfigurationDeserializer.INSTANCE.deserialize(outputStream.toByteArray());
      log.debug("Probe definitions loaded from file {}", probeFilePath);
      configurationUpdater.accept(configuration);
    } catch (IOException ex) {
      log.error("Unable to load config file {}: {}", probeFilePath, ex);
    }
  }

  private static void subscribeConfigurationPoller(ConfigurationUpdater configurationUpdater) {
    configurationPoller.addListener(
        Product.LIVE_DEBUGGING,
        ConfigurationDeserializer.INSTANCE,
        (configKey, newConfig, hinter) -> configurationUpdater.accept(newConfig));

    configurationPoller.addFeaturesListener(
        // what is live debugger feature name?
        "live_debugging",
        DebuggerFeaturesDeserializer.INSTANCE,
        (prod, newConfig, hinter) -> {
          // TODO: disable debugger
          return true;
        });
  }

  static ClassFileTransformer setupInstrumentTheWorldTransformer(
      Config config,
      Instrumentation instrumentation,
      DebuggerContext.Sink sink,
      StatsdMetricForwarder statsdMetricForwarder) {
    log.info("install Instrument-The-World transformer");
    DebuggerContext.init(sink, DebuggerAgent::instrumentTheWorldResolver, statsdMetricForwarder);
    DebuggerTransformer transformer =
        createTransformer(config, new Configuration("", -1, Collections.emptyList()), null);
    instrumentation.addTransformer(transformer);
    return transformer;
  }

  public static String getAgentVersion() {
    return agentVersion;
  }

  private static DebuggerTransformer createTransformer(
      Config config,
      Configuration configuration,
      DebuggerTransformer.InstrumentationListener listener) {
    return new DebuggerTransformer(config, configuration, listener);
  }

  private static Snapshot.ProbeDetails instrumentTheWorldResolver(
      String id, Class<?> callingClass) {
    return Snapshot.ProbeDetails.ITW_PROBE;
  }

  static void stop() {
    if (configurationPoller != null) {
      configurationPoller.stop();
    }
    if (sink != null) {
      sink.stop();
    }
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
