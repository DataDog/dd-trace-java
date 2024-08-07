package com.datadog.debugger.snapshot;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.SPAN_DEBUG;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.exception.Fingerprinter;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.bootstrap.debugger.DebuggerContext.SpanDebugger;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultSpanDebugger implements SpanDebugger {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSpanDebugger.class);

  private final SpanDebuggerProbeManager probeManager;
  private final ConfigurationUpdater configurationUpdater;
  private final ClassNameFiltering classNameFiltering;

  private AgentTaskScheduler taskScheduler = AgentTaskScheduler.INSTANCE;

  public DefaultSpanDebugger(
      ConfigurationUpdater configurationUpdater, ClassNameFiltering classNameFiltering) {
    this.probeManager = new SpanDebuggerProbeManager(classNameFiltering);
    this.configurationUpdater = configurationUpdater;
    this.classNameFiltering = classNameFiltering;
  }

  public SpanDebuggerProbeManager probeManager() {
    return probeManager;
  }

  public AgentTaskScheduler taskScheduler() {
    return taskScheduler;
  }

  public DefaultSpanDebugger taskScheduler(AgentTaskScheduler taskScheduler) {
    this.taskScheduler = taskScheduler;
    return this;
  }

  @Override
  public String captureSnapshot(String signature) {
    StackTraceElement element = findPlaceInStack();
    String fingerprint = Fingerprinter.fingerprint(element);
    if (fingerprint == null) {
      LOGGER.debug("Unable to fingerprint snapshot");
      return null;
    }

    if (!probeManager.isAlreadyInstrumented(fingerprint)) {
      String probeId = probeManager.createProbeForFrame(element, signature);
      if (probeId != null) {
        taskScheduler.execute(
            () -> {
              configurationUpdater.accept(SPAN_DEBUG, probeManager.getProbes());
              probeManager.addFingerprint(fingerprint, probeId);
            });
      }
      return probeId;
    }

    return probeManager.getProbeId(fingerprint);
  }

  private StackTraceElement findPlaceInStack() {
    return StackWalkerFactory.INSTANCE.walk(
        stream -> {
          return stream
              .filter(element -> !classNameFiltering.isExcluded(element.getClassName()))
              .findFirst()
              .orElse(null);
        });
  }
}
