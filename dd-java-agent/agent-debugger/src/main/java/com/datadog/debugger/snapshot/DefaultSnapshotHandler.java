package com.datadog.debugger.snapshot;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.DEBUG;
import static com.datadog.debugger.exception.Fingerprinter.bytesToHex;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.exception.ExceptionProbeManager.ThrowableState;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.AgentTaskScheduler;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultSnapshotHandler implements DebuggerContext.SnapshotHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSnapshotHandler.class);
  public static final String SNAPSHOT_ID_TAG_FMT = "_dd.%s_location.snapshot_id";

  private final SnapshotProbeManager probeManager;
  private final ConfigurationUpdater configurationUpdater;

  private final ClassNameFiltering classNameFiltering;

  public DefaultSnapshotHandler(
      SnapshotProbeManager probeManager,
      ConfigurationUpdater configurationUpdater,
      ClassNameFiltering classNameFiltering) {
    this.probeManager = probeManager;
    this.configurationUpdater = configurationUpdater;
    this.classNameFiltering = classNameFiltering;
  }

  @Override
  public void handleSnapshot(AgentSpan span, StackTraceElement element) {
    String fingerprint = fingerprint(element);
    if (fingerprint == null) {
      LOGGER.debug("Unable to fingerprint snapshot");
      return;
    }

    if (!probeManager.isAlreadyInstrumented(fingerprint)) {
      System.out.println("not already instrumented");
      if (probeManager.createProbesForException(element)) {
        AgentTaskScheduler.INSTANCE.execute(
            () -> {
              configurationUpdater.accept(DEBUG, probeManager.getProbes());
              probeManager.addFingerprint(fingerprint);
            });
      }
    }
  }

  private static void processSnapshotsAndSetTags(
      Throwable t, AgentSpan span, ThrowableState state, StackTraceElement[] trace) {
    List<Snapshot> snapshots = state.getSnapshots();
    for (int i = 0; i < snapshots.size(); i++) {
      Snapshot snapshot = snapshots.get(i);
      span.setTag(SNAPSHOT_ID_TAG_FMT, snapshot.getId());
      LOGGER.debug(
          "add tag to span[{}]: {}: {}", span.getSpanId(), SNAPSHOT_ID_TAG_FMT, snapshot.getId());
      DebuggerAgent.getSink().addSnapshot(snapshot);
    }
  }

  public static String fingerprint(StackTraceElement element) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(element.toString().getBytes());
      return bytesToHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      LOGGER.debug("Unable to find digest algorithm SHA-256", e);
      return null;
    }
  }
}
