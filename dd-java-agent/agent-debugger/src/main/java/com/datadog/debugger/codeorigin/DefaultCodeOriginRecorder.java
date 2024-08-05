package com.datadog.debugger.codeorigin;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.SPAN_DEBUG;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.exception.Fingerprinter;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.bootstrap.debugger.DebuggerContext.CodeOriginRecorder;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultCodeOriginRecorder implements CodeOriginRecorder {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultCodeOriginRecorder.class);

  private final CodeOriginProbeManager probeManager;
  private final ConfigurationUpdater configurationUpdater;
  private final ClassNameFiltering classNameFiltering;

  private AgentTaskScheduler taskScheduler = AgentTaskScheduler.INSTANCE;

  public DefaultCodeOriginRecorder(
      ConfigurationUpdater configurationUpdater, ClassNameFiltering classNameFiltering) {
    this.probeManager = new CodeOriginProbeManager(classNameFiltering);
    this.configurationUpdater = configurationUpdater;
    this.classNameFiltering = classNameFiltering;
  }

  public CodeOriginProbeManager probeManager() {
    return probeManager;
  }

  public AgentTaskScheduler taskScheduler() {
    return taskScheduler;
  }

  public DefaultCodeOriginRecorder taskScheduler(AgentTaskScheduler taskScheduler) {
    this.taskScheduler = taskScheduler;
    return this;
  }

  @Override
  public String captureCodeOrigin(String signature) {
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
          List<StackTraceElement> list = stream.collect(Collectors.toList());
          list =
              list.stream()
                  .filter(element -> !classNameFiltering.isExcluded(element.getClassName()))
                  .collect(Collectors.toList());

          return list.stream().findFirst().orElse(null);
        });
  }
}
