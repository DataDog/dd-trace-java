package com.datadog.debugger.agent;

import static datadog.communication.http.OkHttpUtils.buildHttpClient;
import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import com.datadog.debugger.poller.ConfigurationPoller;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.uploader.BatchUploader;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.monitor.Monitoring;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Objects;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Debugger agent implementation */
public class DebuggerAgent {
  private static final Logger log = LoggerFactory.getLogger(DebuggerAgent.class);
  private static ConfigurationPoller configurationPoller;
  private static DebuggerSink sink;
  private static String agentVersion;

  public static synchronized void run(Instrumentation instrumentation) {
    if (!Config.get().isDebuggerEnabled()) {
      log.info("Debugger agent disabled");
      return;
    }

    String finalDebuggerSnapshotUrl = Config.get().getFinalDebuggerSnapshotUrl();
    String agentUrl = Config.get().getAgentUrl();
    boolean isSnapshotUploadThroughAgent = Objects.equals(finalDebuggerSnapshotUrl, agentUrl);
    String configEndpoint = null;
    DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery = getDdAgentFeaturesDiscovery();
    ddAgentFeaturesDiscovery.discover();
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
    configEndpoint = ddAgentFeaturesDiscovery.getConfigEndpoint();

    sink = new DebuggerSink(Config.get());
    sink.start();
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            instrumentation, DebuggerAgent::createTransformer, Config.get(), sink);
    StatsdMetricForwarder statsdMetricForwarder = new StatsdMetricForwarder(Config.get());
    DebuggerContext.init(sink, configurationUpdater, statsdMetricForwarder);
    DebuggerContext.initClassFilter(new DenyListHelper(null)); // default hard coded deny list
    if (Config.get().isDebuggerInstrumentTheWorld()) {
      setupInstrumentTheWorldTransformer(
          Config.get(), instrumentation, sink, statsdMetricForwarder);
    }
    configurationPoller =
        new ConfigurationPoller(Config.get(), configurationUpdater, configEndpoint);
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

  private static DDAgentFeaturesDiscovery getDdAgentFeaturesDiscovery() {
    HttpUrl httpUrl = HttpUrl.get(Config.get().getFinalDebuggerProbeUrl());
    log.debug("Datadog agent features discovery from: {}", httpUrl);
    OkHttpClient client = buildHttpClient(httpUrl, 30 * 1000);
    return new DDAgentFeaturesDiscovery(client, Monitoring.DISABLED, httpUrl, false, false);
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
