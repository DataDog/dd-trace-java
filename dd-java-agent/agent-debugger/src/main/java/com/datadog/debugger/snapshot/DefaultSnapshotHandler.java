package com.datadog.debugger.snapshot;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.DEBUG;
import static com.datadog.debugger.exception.Fingerprinter.bytesToHex;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.AgentTaskScheduler;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultSnapshotHandler implements DebuggerContext.SnapshotHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSnapshotHandler.class);

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
  public boolean isExcluded(String className) {
    return classNameFiltering.isExcluded(className);
  }

  @Override
  public String handleSnapshot(
      AgentSpan span, boolean isEntrySpanOrigin, StackTraceElement element) {
    String fingerprint = fingerprint(element);
    if (fingerprint == null) {
      LOGGER.debug("Unable to fingerprint snapshot");
      return null;
    }

    if (!probeManager.isAlreadyInstrumented(fingerprint)) {
      String probeId = probeManager.createProbesForException(isEntrySpanOrigin, element);
      if (probeId != null) {
        AgentTaskScheduler.INSTANCE.execute(
            () -> {
              configurationUpdater.accept(DEBUG, probeManager.getProbes());
              probeManager.addFingerprint(fingerprint, probeId);
            });
      }
      return probeId;
    }

    return probeManager.getProbeId(fingerprint);
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
